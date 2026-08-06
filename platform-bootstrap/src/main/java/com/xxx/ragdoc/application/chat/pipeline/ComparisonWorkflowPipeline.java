package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * PR-3.4 / EMS-PR3: 比较固定工作流 + (轻量)证据补全工作流。
 *
 * <p><b>比较流程 (任务文档 §3.4)</b>:
 *
 * <pre>
 *   RouterDecision.entities → A, B
 *       ↓
 *   cmdA = cmd.withQuery(constructSubQuery(origQuery, A))   ; cmdB 同理
 *       ↓
 *   resultA = chatService.chat(cmdA, traceId, conv)         ; resultB 同理
 *       ↓
 *   检查 双方 Evidence:citations 非空 → 否则 REFUSE_NO_EVIDENCE 短路(单终态, 不调下游)
 *       ↓
 *   合并 answer: "A 面:\n{aAnswer}\n\nB 面:\n{bAnswer}"(PR-3.4 第一版, 不引入新 LLM 综合调用)
 *   合并 citations: aCitations + bCitations (去重 by chunkId)
 * </pre>
 *
 * <p>第一版<b>不做</b>: 让 LLM 显式生成结构化 "X 比 Y 更好, 因为 ..." 对比结论。理由:
 *
 * <ol>
 *   <li>避免引入非项目既有 prompt 模板(项目 prompt 演进由 ChatMessages 管)。
 *   <li>"两次副作用 retrieve + 一次直白拼接" 对 retrieval 评测(每方 Recall@K / 引用 Faithfulness)和 citation
 *       都能落地测评, 已比 Classic 多走一步。
 *   <li>PR-8 LLM-based Claim-Citation 时再加综合 prompt。
 * </ol>
 *
 * <p><b>降级/守门</b>(单终态契约):
 *
 * <ul>
 *   <li>A 或 B 任一为非-OK(EMPTY_KB/NO_RECALL/LLM_DEGRADED 等)且 citations 空 →
 *       return {@link ChatResult#of} stateHint=NO_RECALL + 友好提示 "至少一方缺乏证据,无法比较"
 *   <li>两方都成功且有 citations → 合并答案 + 合并 citations, stateHint=OK
 *   <li>Sub-query 抛异常 → 上抛 (GlobalExceptionHandler 接管 500), 不假装成功
 * </ul>
 *
 * <p><b>SSE 策略 (PR-3.4 第一版, 不破坏单终态)</b>: SSE 在 PR-3.4 仍走 Classic 流式委托
 * (FixedWorkflow 用同步路径连查两次 retrieve + LLM 已经带来 2x 成本, 用流式还需 buffer token, 留 PR-4)。
 * SSE 输出由 Classic 链路产, 单终态契约(PR-0)保持不变。
 *
 * <p><b>ACL</b>: A、B 两次 {@link ChatService#chat} 都经过 RetrieveService → AccessScope sentinel, 无权文档
 * 不会进任一方的 citations, 自然不进合并结果。
 */
@Slf4j
@Component
public class ComparisonWorkflowPipeline implements ChatPipeline {

    public static final String PIPELINE_VERSION = "comparison-workflow-v1";
    /** 比较工作流至少要看到的 A/B 两方证据数量阈值 (任一方为 0 即 REFUSE_NO_EVIDENCE)。 */
    public static final int MIN_CITATIONS_PER_SIDE = 1;

    private final ChatService chatService;

    @Autowired
    public ComparisonWorkflowPipeline(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public PipelineType type() {
        return PipelineType.FIXED_WORKFLOW;
    }

    @Override
    public ChatResult execute(ChatCommand command, ChatExecutionContext context) {
        Pair ab = extractComparisonPair(command, context);
        if (ab == null) {
            // Router 误派发 (entities 不足) → 回退 Classic 不假装 workflow 成功
            log.info(
                    "pipeline.workflow.fallback_no_ab request_id={}, trace_id={}, entities={}",
                    context.requestId(),
                    context.traceId().value(),
                    context.routerDecision() != null ? context.routerDecision().entities() : "[]");
            return chatService.chat(command, context.traceId(), command.conversationId());
        }

        log.info(
                "pipeline.workflow.execute request_id={}, trace_id={}, A='{}', B='{}'",
                context.requestId(),
                context.traceId().value(),
                ab.a,
                ab.b);

        // 构造 A、B 子 query: 原始 question 作为 global context, 显式追加 subject。例
        //   orig="比较 v1.0 与 v2.0 权限差异", A="v1.0", B="v2.0"
        //   → subQueryA = "关于比较 v1.0 与 v2.0 权限差异: 查找与 v1.0 相关的内容"
        //   (用通用模板, 不引入仓库外事实)
        String subQueryA = "关于「" + command.query() + "」, 请聚焦与「" + ab.a + "」直接相关的事实";
        String subQueryB = "关于「" + command.query() + "」, 请聚焦与「" + ab.b + "」直接相关的事实";

        ChatCommand cmdA = command.withQuery(subQueryA);
        ChatCommand cmdB = command.withQuery(subQueryB);

        ChatResult resultA =
                chatService.chat(cmdA, context.traceId(), command.conversationId());
        ChatResult resultB =
                chatService.chat(cmdB, context.traceId(), command.conversationId());

        return mergeAndAssemble(resultA, resultB, command, context, ab);
    }

    /**
     * PR-3.4: SSE 第一版走 Classic 流式 compose。完整 Evidence→Answer 流式合并留后续。
     *
     * <p>不破坏单终态(PR-0): 用 ClassicRagPipeline 的 stream 即可保证 DoneEvent 唯一。
     */
    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command, ChatExecutionContext context) {
        log.info(
                "pipeline.workflow.stream_fallback_to_classic request_id={}, trace_id={}",
                context.requestId(),
                context.traceId().value());
        return chatService.chatStream(command, context.traceId());
    }

    // ─── 内部 ────────────────────────────────────────────────

    /**
     * 比较对象 A/B 配对 (公开让测试与未来 trace 可读)。null 表示无 A/B (回退 Classic)。
     *
     * @param a 第一个比较对象
     * @param b 第二个比较对象
     */
    public record Pair(String a, String b) {}

    /**
     * 测试/trace 友好: 公开的 Pair 抽取方法。返回 null 表示无 A/B 可比较 → 调用方回退 Classic。
     */
    public static Pair extractComparisonPair(ChatCommand cmd, ChatExecutionContext ctx) {
        RouterDecision d = ctx.routerDecision();
        List<String> ents = d != null ? d.entities() : List.of();
        String a = null;
        String b = null;
        if (ents != null && ents.size() >= 2) {
            a = ents.get(0);
            b = ents.get(1);
        } else if (d != null && d.filters() != null) {
            a = firstString(d.filters().get("versions"));
            b = a == null ? null : secondString(d.filters().get("versions"));
            if (a == null) {
                a = firstString(d.filters().get("products"));
                b = a == null ? null : secondString(d.filters().get("products"));
            }
        }
        if (a == null || b == null) return null;
        return new Pair(a, b);
    }

    private static String firstString(Object o) {
        if (o instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) return s;
        return null;
    }

    private static String secondString(Object o) {
        if (o instanceof List<?> list && list.size() >= 2 && list.get(1) instanceof String s) return s;
        return null;
    }

    /**
     * 合并 A/B 两次结果, 不引入新 LLM 综合调用。
     *
     * <p>注意: 单终态契约 (SSE 不在此路径) + 不写 success trace (那是 ChatService 自己已写过 A/B 两次)。
     */
    static ChatResult mergeAndAssemble(
            ChatResult a, ChatResult b, ChatCommand orig, ChatExecutionContext ctx, Pair ab) {
        List<ChatResult.Citation> aCits = nonNull(a.citations());
        List<ChatResult.Citation> bCits = nonNull(b.citations());

        // 任一方 Evidence 不足 → 单终态 NO_RECALL, 拒绝拼凑
        if (aCits.isEmpty() || bCits.isEmpty()) {
            log.info(
                    "pipeline.workflow.no_evidence aCits={}, bCits={}, state_a={}, state_b={}",
                    aCits.size(), bCits.size(), a.stateHint(), b.stateHint());
            return ChatResult.of(
                    StateHint.NO_RECALL,
                    "至少一方 (A=" + ab.a + " 或 B=" + ab.b + ") 缺乏可引用的文档证据, 暂时无法给出比较答案。",
                    ctx.traceId());
        }

        // 合并 (默认双方都有 A-vs-B 的实际内容)
        List<ChatResult.Citation> merged = mergeCitations(aCits, bCits);
        String mergedAnswer =
                "关于「"
                        + orig.query()
                        + "」从两个角度对比:\n\n"
                        + "【关于 "
                        + ab.a
                        + "】\n"
                        + a.answer()
                        + "\n\n"
                        + "【关于 "
                        + ab.b
                        + "】\n"
                        + b.answer();

        return new ChatResult(
                mergedAnswer,
                merged,
                StateHint.OK,
                ctx.traceId(),
                null, // verification 留空; Citation Verifier 默认 disabled
                null // PR-1 evidenceSnapshot 由 ChatService 内部产, workflow 不重组该字段
                );
    }

    /** 简单 dedup by chunkId; 同 chunkId 在双方都出现时只取 A。 */
    static List<ChatResult.Citation> mergeCitations(
            List<ChatResult.Citation> aCits, List<ChatResult.Citation> bCits) {
        List<ChatResult.Citation> out = new ArrayList<>(aCits);
        java.util.Set<Long> seenChunkIds = new java.util.HashSet<>();
        for (ChatResult.Citation c : aCits) {
            if (c.chunkId() != null) seenChunkIds.add(c.chunkId());
        }
        for (ChatResult.Citation c : bCits) {
            // chunkId 为 null 时不参与 dedup 但照常放入(避免吞证据)
            if (c.chunkId() == null || seenChunkIds.add(c.chunkId())) out.add(c);
        }
        return out;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
