package com.xxx.ragdoc.infrastructure.conversation;

import com.xxx.ragdoc.application.chat.ConversationProperties;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext.Turn;
import com.xxx.ragdoc.application.chat.conversation.port.ConversationStore;
import com.xxx.ragdoc.application.chat.conversation.port.HistoryCompressorPort;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.infrastructure.llm.LlmRouter;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 多轮对话历史压缩器, ADR-0011 §6 + §9。
 *
 * <p>当 ConversationContext.recentTurns 累积 ≥ 6 时, 把老的 N-3 turn 用 fallback LLM 压成 rollingSummary
 * (Tier S), 保留最近 3 turn 在 BufferWindow (Tier B) 不动。
 *
 * <h3>关键实现细节 (ADR-0011 §9)</h3>
 *
 * <ul>
 *   <li>@Async("historyCompressorPool") 异步执行, 不阻塞用户的 chat response
 *   <li>独立 CircuitBreaker instance {@code "summary-llm"}, 与主 LLM / rewrite-llm 完全隔离
 *   <li>走 fallback route (DeepSeek-V3 便宜) 而非主 GLM-4-plus, 省 token 钱
 *   <li>Quality gate 1: 摘要长度 ≥ 10 char (防 LLM 出 "" 或乱字符)
 *   <li>Quality gate 2: 关键实体保留率 (G4 fidelity gate, C7 评测套件实施 offline 验)
 *   <li>失败/quality rejected → silent log + 下次 save 仍会再触发 (debounce 保证不雪崩)
 *   <li>命中 cb 熔断 → catch CallNotPermittedException, 等下个 turn 重试
 * </ul>
 *
 * <p>{@code @ConditionalOnProperty rag.conversation.compress=true} 启用本类 (双 flag 守门: enabled=true
 * 主开关 + compress=true 压缩专项开关)。
 *
 * @author Phase 1 / C6 (ADR-0011)
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.conversation", name = "compress", havingValue = "true")
public class HistoryCompressor implements HistoryCompressorPort {

    /** 摘要 LLM 输出最小长度, 短于此视为 LLM 异常 → 拒收。 */
    private static final int MIN_SUMMARY_LEN = 10;

    private static final String SUMMARY_PROMPT_TEMPLATE =
            """
            你是 Spring Cloud Alibaba 中间件文档问答系统的多轮对话摘要助手。
            请把以下"已有摘要 + 新增若干轮对话"合并成一个简明摘要。

            已有摘要 (可能为空):
            %s

            新增对话 (要合并进去):
            %s

            要求:
            1. 输出 ≤ 500 字, 中文
            2. 保留领域实体 (组件名 / 版本号 / 配置 key / 阈值)
            3. 保留时间顺序
            4. 丢弃小聊 / 提示语 / 免责声明 / 措辞性内容
            5. 不要回答问题, 只摘要

            合并后的摘要: """;

    private final ChatClient summaryClient;
    private final CircuitBreaker cb;
    private final ConversationStore store;
    private final ConversationProperties props;
    private final RagdocMetrics metrics;

    public HistoryCompressor(
            LlmRouter llmRouter,
            CircuitBreakerRegistry cbRegistry,
            ConversationStore store,
            ConversationProperties props,
            RagdocMetrics metrics) {
        // 走 fallback LLM (DeepSeek-V3 便宜); LlmRouter 没 fallback 时退到 primary (rare)
        this.summaryClient = llmRouter.getRouteClient("fallback");
        // 单独 cb instance "summary-llm", 与主 LLM / rewrite-llm 完全隔离
        this.cb = cbRegistry.circuitBreaker("summary-llm");
        this.store = store;
        this.props = props;
        this.metrics = metrics;
        log.info(
                "HistoryCompressor enabled, route=fallback, cb=summary-llm (state={}), threshold={}",
                cb.getState(),
                props.getCompressThreshold());
    }

    /**
     * 异步压缩: 把 conversationId 的 ctx 内老的 (N - maxRecentTurns) turn 喂 LLM 生成摘要, 替换 ctx。
     *
     * <p>fire-and-forget — 调用方 (ChatService 在 OK turn save 后) 不等返回。
     *
     * <p>关键安全性: 双重 check needsCompression, 防 submit 之后 ctx 已经被其他任务处理过。
     */
    @Async("historyCompressorPool")
    public void compress(String conversationId) {
        long t0 = System.currentTimeMillis();
        ConversationContext ctx;
        try {
            ctx = store.findById(conversationId).orElse(null);
        } catch (Exception e) {
            // store 异常 silent log (内部应该已 silent); 不挂后台线程
            log.warn("compress.load_failed id={}, reason={}", conversationId, e.getMessage());
            metrics.incrementCompression("load_failed");
            return;
        }
        if (ctx == null) {
            // ctx 已过期 / 用户离线 / clear 了 — 没必要压
            metrics.incrementCompression("no_ctx");
            return;
        }
        // 双重 check: Submit 时 ChatService 验过, 但队列等待期间 ctx 可能已被另一并发任务压过
        if (!ctx.needsCompression(props.getCompressThreshold())) {
            metrics.incrementCompression("skipped_no_need");
            return;
        }

        int keepN = props.getMaxRecentTurns();
        List<Turn> recent = ctx.recentTurns();
        int toCompress = recent.size() - keepN;
        if (toCompress <= 0) {
            metrics.incrementCompression("skipped_no_need");
            return;
        }
        List<Turn> oldTurns = recent.subList(0, toCompress);
        List<Turn> keepTurns = recent.subList(toCompress, recent.size());

        String prompt =
                String.format(
                        SUMMARY_PROMPT_TEMPLATE,
                        ctx.rollingSummary() == null ? "(空)" : ctx.rollingSummary(),
                        formatTurns(oldTurns));

        String newSummary;
        try {
            newSummary =
                    cb.executeSupplier(
                            () -> {
                                try {
                                    return summaryClient.chat(prompt, List.of());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });
        } catch (Exception e) {
            metrics.incrementCompression("failed");
            log.warn(
                    "compress.llm_failed id={}, reason={} — will retry next turn",
                    conversationId,
                    rootCause(e));
            return;
        }

        // Quality gate 1: 长度
        if (newSummary == null || newSummary.trim().length() < MIN_SUMMARY_LEN) {
            metrics.incrementCompression("invalid");
            log.warn(
                    "compress.quality_rejected id={} reason=too_short, summary_len={}",
                    conversationId,
                    newSummary == null ? 0 : newSummary.length());
            return;
        }

        // 保存: ctx replaced by withCompression (保留 totalTurnCount 审计用)
        // P0 修复(lost-update): load → LLM(数十秒) → save 期间, 用户新 turn 的 appendTurn+save
        // 会被本处的旧快照整体覆盖, 丢掉最新对话。save 前重新 load 一次做合并:
        //   - 期间追加了新 turn → 在压缩结果之上保留追加部分(append-only 语义, 按位置切)
        //   - 期间无变化 → 存压缩结果
        //   - 期间 recentTurns 变少 → 另一并发压缩已生效, 放弃本次(下个 turn 会再触发)
        // 残余竞态窗口仅剩 re-load→save 的毫秒级; 彻底消除需 store 层 CAS(Redis WATCH/version),
        // 属 ConversationStore port 扩展, 见 ADR-0011 后续。
        try {
            ConversationContext updated =
                    ctx.withCompression(newSummary.trim(), keepTurns, Instant.now());
            ConversationContext toSave = mergeWithLatest(conversationId, ctx, updated, keepTurns, newSummary.trim());
            if (toSave == null) {
                metrics.incrementCompression("superseded");
                log.info("compress.superseded id={} — 并发压缩已生效, 跳过", conversationId);
                return;
            }
            store.save(toSave);
            metrics.incrementCompression("ok");
            log.info(
                    "compress.done id={}, compressed={}, kept={}, summary_len={}, took={}ms",
                    conversationId,
                    toCompress,
                    toSave.recentTurns() == null ? keepN : toSave.recentTurns().size(),
                    newSummary.length(),
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            metrics.incrementCompression("save_failed");
            log.warn("compress.save_failed id={}, reason={}", conversationId, e.getMessage());
        }
    }

    /**
     * 把压缩结果与 save 前的最新 ctx 合并; 返 null 表示本次压缩已被并发任务取代应放弃。
     *
     * <p>{@code baseTurns} 是压缩发起时的 recentTurns 快照 — latest 以它为前缀追加的 append-only
     * 假设由 ChatService.appendTurn 保证(只在尾部追加)。
     */
    private ConversationContext mergeWithLatest(
            String conversationId,
            ConversationContext snapshot,
            ConversationContext compressed,
            List<Turn> keepTurns,
            String summary) {
        ConversationContext latest;
        try {
            latest = store.findById(conversationId).orElse(null);
        } catch (Exception e) {
            // re-load 失败: 退回直接存压缩结果(旧快照), 不比直接放弃好但也不更坏
            log.warn("compress.recheck_load_failed id={}, reason={}", conversationId, e.getMessage());
            return compressed;
        }
        if (latest == null || latest.recentTurns() == null) {
            return compressed; // 期间被 clear/过期 — 压缩结果作全新 ctx 存回
        }
        List<Turn> base = snapshot.recentTurns() == null ? List.of() : snapshot.recentTurns();
        List<Turn> latestTurns = latest.recentTurns();
        if (latestTurns.size() < base.size()) {
            return null; // 被并发压缩处理过 — 放弃
        }
        if (latestTurns.size() == base.size()) {
            return compressed; // 无新增, 直接存
        }
        // 有新 turn 追加: 压缩产物 + 保留追加部分
        List<Turn> appended = latestTurns.subList(base.size(), latestTurns.size());
        List<Turn> merged = new java.util.ArrayList<>(keepTurns);
        merged.addAll(appended);
        return snapshot.withCompression(summary, merged, Instant.now());
    }

    private static String formatTurns(List<Turn> turns) {
        StringBuilder sb = new StringBuilder();
        for (Turn t : turns) {
            sb.append("Q: ").append(t.userQuery()).append("\n");
            // botAnswer 不截断 (LLM 用 history 内全文压缩, 跟 PromptAssembler 不同 — 那是塞 LLM
            // context 受 token 限制, 这里是单独 LLM 调用专做压缩)
            sb.append("A: ").append(t.botAnswer() == null ? "" : t.botAnswer()).append("\n");
        }
        return sb.toString().trim();
    }

    private static String rootCause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
