package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.domain.shared.PipelineType;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * PR-3.3 / EMS-PR3: Targeted RAG — 精确元数据查询 (版本号 / 错误码 / 时间范围 / 章节-产品定位)。
 *
 * <p><b>实现策略 (任务文档 §五要求"提取+委托+复用", 不重写)</b>:
 *
 * <ol>
 *   <li>从 {@link ChatExecutionContext#routerDecision()} 读取 Router 抽出的 versions / products
 *   <li>把 versions[0] 映射到 {@link ChatCommand#version()}, products[0] 映射到 {@link
 *       ChatCommand#source()} — 这两字段已经是 {@code RetrieveService} 既有的 {@code MetadataFilter} 维度,
 *       无需新基础设施
 *   <li>委托 {@link ChatService#chat}/{@code chatStream}, 所以 Evidence Snapshot / Citation / Rerank /
 *       Citation Verify / Trace 全部继承 Classic RAG
 * </ol>
 *
 * <p><b>设计取舍</b>:
 *
 * <ul>
 *   <li>PR-3.3 不再触发 standalone keyword search service (项目本来就无 BM25/Elastic); "keyword" 在 本仓库 =
 *       vector filter 过滤后 + rerank 的 hybrid 模式, 通过 {@code cmd.source/version} 做 metadata filter
 *   <li>version 提示命中: 用户问 "v2.3 新增接口" → cmd.version="v2.3" → MetadataFilter 锁定 v2.3 doc → 限定空间
 *   <li>source 提示命中: 用户问 "Nacos 的健康检查在哪一节" → cmd.source=Nacos → MetadataFilter 锁定 Nacos
 *   <li>不支持把 years/quarters 当独立 filter (DB 时间字段是 created_at 不在 MetadataFilter 里); 这些 token 仍在 query
 *       字面上, 由 vector search 间接匹配
 *   <li>不修改 RetrieveService / Reranker / Citation Verifier, 不引入新组件, 不破坏 Evidence Snapshot
 * </ul>
 *
 * <p><b>ACL 保留</b>: 不通过 Pipeline 旁路, 全部经 {@code ChatService.chat} → {@code
 * RetrieveService.retrieve}, AccessScope sentinel / MilvusFilterExprBuilder 仍生效, 无权 chunk 不会进
 * evidence。
 *
 * <p><b>降级</b>: 若无任何 version/product 提示可映射 (例如 Router 仅抽到 errorCodes), 等价回退 Classic (相当于一次普通
 * ChatService.chat 调用, query 仍含原错误码文本 — vector match 给出最近答案)。
 */
@Slf4j
@Component
public class TargetedRagPipeline implements ChatPipeline {

    /** Pipeline 版本, 调整映射规则时 bump。 */
    public static final String PIPELINE_VERSION = "targeted-rag-v1";

    private final ChatService chatService;

    @Autowired
    public TargetedRagPipeline(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public PipelineType type() {
        return PipelineType.TARGETED_RAG;
    }

    @Override
    public ChatResult execute(ChatCommand command, ChatExecutionContext context) {
        ChatCommand targeted = applyTargetedFilters(command, context);
        log.info(
                "pipeline.targeted.execute request_id={}, trace_id={}, source={}, version={}, topK={}",
                context.requestId(),
                context.traceId().value(),
                targeted.source(),
                targeted.version(),
                targeted.topK());
        return chatService.chat(targeted, context.traceId(), targeted.conversationId());
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command, ChatExecutionContext context) {
        ChatCommand targeted = applyTargetedFilters(command, context);
        log.info(
                "pipeline.targeted.stream request_id={}, trace_id={}, source={}, version={}",
                context.requestId(),
                context.traceId().value(),
                targeted.source(),
                targeted.version());
        return chatService.chatStream(targeted, context.traceId(), targeted.conversationId());
    }

    /**
     * 把 RouterDecision.filters + entities 映射到 ChatCommand.source()/version()。
     *
     * <p>规则 (简单 + 可解释, 评测可对每个 caseId 核对):
     *
     * <ul>
     *   <li>version: 优先 cmd.version (用户显式) → fallback routerDecision.filters["versions"][0]
     *   <li>source: 优先 cmd.source (用户显式) → fallback routerDecision.filters["products"][0]
     *   <li>query / docId / topK / language 原样保留; 不拼接 errorCodes/years/quarters 防止改变 vector 嵌入分布
     *   <li>没拿到任何过滤 → 返回原 command, 等价 Classic 路径 (零回归)
     * </ul>
     *
     * <p>对 unit test 友好: 公开 package-private, 让 TargetedRagPipelineTest 直接断言映射行为。
     */
    static ChatCommand applyTargetedFilters(ChatCommand orig, ChatExecutionContext ctx) {
        RouterDecision d = ctx.routerDecision();
        Map<String, Object> filters = d != null ? d.filters() : Map.of();

        String version = orig.version();
        if (version == null) {
            Object versions = filters.get("versions");
            if (versions instanceof List<?> list
                    && !list.isEmpty()
                    && list.get(0) instanceof String s) {
                version = s;
            }
        }
        String source = orig.source();
        if (source == null) {
            Object products = filters.get("products");
            if (products instanceof List<?> list
                    && !list.isEmpty()
                    && list.get(0) instanceof String s) {
                source = s;
            }
        }

        // 没新增过滤 → 不重建 cmd, 等价 Classic 检索
        boolean versionChanged = version != null && !version.equals(orig.version());
        boolean sourceChanged = source != null && !source.equals(orig.source());
        if (!versionChanged && !sourceChanged) {
            return orig;
        }
        return new ChatCommand(
                orig.query(),
                orig.docId(),
                orig.topK(),
                source != null ? source : orig.source(),
                version != null ? version : orig.version(),
                orig.language(),
                orig.conversationId());
    }
}
