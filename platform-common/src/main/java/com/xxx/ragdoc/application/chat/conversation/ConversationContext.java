package com.xxx.ragdoc.application.chat.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xxx.ragdoc.domain.shared.StateHint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 多轮对话上下文根聚合, ADR-0011 §1。
 *
 * <p>本类承载一个 conversation 的全部状态:
 *
 * <ul>
 *   <li>{@code recentTurns} — 三层 Memory 的 Tier B (Buffer Window), 最近 N turn 原文, 用于指代还原
 *   <li>{@code rollingSummary} — 三层 Memory 的 Tier S (Rolling Summary), 老 turn 的 LLM 压缩, 概念级事实保留
 *   <li>{@code tenantScope} — source/version/docId 继承基线, 防"切 doc 后 history 仍带老 doc 的 retrieval 上下文"
 * </ul>
 *
 * <h3>"暂存" vs "记忆"的判定边界</h3>
 *
 * 写进本对象 + ConversationStore.save() → 是记忆; 反之是暂存。详见 ADR-0011 §11.5。
 *
 * <h3>不可变设计</h3>
 *
 * final class + private final 字段 + 构造器一次定型, 任何状态变更走 {@link #appendTurn(Turn)}
 * / {@link #withCompression(String, List, Instant)} 等 with- 方法返回新实例。这避免了:
 *
 * <ul>
 *   <li>异步压缩线程与主 chat 线程 race condition
 *   <li>多 turn 期间 ctx 被误改 (history.json 反序列化回的实例随时被复读)
 * </ul>
 *
 * @author Phase 1 / C1 (ADR-0011)
 */
public final class ConversationContext {

    private final String conversationId;
    private final String userId;
    private final String tenantScope; // 可空: "source:sentinel,version:2.x" 或 null

    private final List<Turn> recentTurns;
    private final String rollingSummary; // 可空
    private final int totalTurnCount;

    private final Instant createdAt;
    private final Instant lastActiveAt;
    private final Instant summaryUpdatedAt; // 可空, debounce 用

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public ConversationContext(
            @JsonProperty("conversationId") String conversationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("tenantScope") String tenantScope,
            @JsonProperty("recentTurns") List<Turn> recentTurns,
            @JsonProperty("rollingSummary") String rollingSummary,
            @JsonProperty("totalTurnCount") int totalTurnCount,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("lastActiveAt") Instant lastActiveAt,
            @JsonProperty("summaryUpdatedAt") Instant summaryUpdatedAt) {
        this.conversationId =
                Objects.requireNonNull(conversationId, "conversationId 不能为空");
        this.userId = userId;
        this.tenantScope = tenantScope;
        this.recentTurns =
                recentTurns == null
                        ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(recentTurns));
        this.rollingSummary = rollingSummary;
        this.totalTurnCount = totalTurnCount;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
        this.summaryUpdatedAt = summaryUpdatedAt;
    }

    /** 新建空 ctx, 用于会话首次 / TTL 过期 / Redis 异常 fallback stateless。 */
    public static ConversationContext empty(String conversationId) {
        Instant now = Instant.now();
        return new ConversationContext(
                conversationId, null, null, List.of(), null, 0, now, now, null);
    }

    /** memory 是否生效: recentTurns 或 rollingSummary 任一非空。 */
    public boolean isEnabled() {
        return (recentTurns != null && !recentTurns.isEmpty()) || rollingSummary != null;
    }

    /**
     * 是否需要触发异步压缩。ADR-0011 §6 + §9.3 debounce。
     *
     * <p>触发条件(全部满足):
     *
     * <ol>
     *   <li>recentTurns 数量 ≥ threshold (默认 6)
     *   <li>距上次压缩 ≥ 1 分钟 (debounce, 防用户连发 6/7/8 turn 重复 LLM 调用)
     * </ol>
     */
    public boolean needsCompression(int threshold) {
        if (recentTurns == null || recentTurns.size() < threshold) return false;
        if (summaryUpdatedAt == null) return true;
        return java.time.Duration.between(summaryUpdatedAt, Instant.now()).toMinutes() >= 1;
    }

    /**
     * 追加一个新 turn, 返回新 ctx。不写入 store(由调用方决定, ChatService 仅 OK turn 调 save)。
     *
     * @param newTurn 必须 state=OK (硬规则 G3, 调用方自检)
     */
    public ConversationContext appendTurn(Turn newTurn) {
        List<Turn> updated = new ArrayList<>(this.recentTurns);
        updated.add(newTurn);
        return new ConversationContext(
                conversationId,
                userId,
                tenantScope,
                updated,
                rollingSummary,
                totalTurnCount + 1,
                createdAt,
                Instant.now(), // refresh lastActiveAt
                summaryUpdatedAt);
    }

    /**
     * 压缩完成时调用, 把老 turn 替换为 summary, recentTurns 保留最近若干。
     *
     * @param newSummary LLM 压缩生成的新摘要 (会跟现有 rollingSummary 不叠加, 由调用方拼好)
     * @param keepTurns 压缩后保留的近 recentTurns (默认最近 3 个)
     * @param compressFinishedAt 压缩完成时间戳, 写入 summaryUpdatedAt 用于 debounce
     */
    public ConversationContext withCompression(
            String newSummary, List<Turn> keepTurns, Instant compressFinishedAt) {
        return new ConversationContext(
                conversationId,
                userId,
                tenantScope,
                keepTurns,
                newSummary,
                totalTurnCount, // 累计数不变 (不归零, 审计用)
                createdAt,
                Instant.now(),
                compressFinishedAt);
    }

    /** Antipollution check: 验证这 turn 是 OK 才允许写入。ADR-0011 §8.2 G3。 */
    public static boolean isWritable(StateHint state) {
        return state == StateHint.OK;
    }

    @JsonProperty("conversationId")
    public String conversationId() {
        return conversationId;
    }

    @JsonProperty("userId")
    public String userId() {
        return userId;
    }

    @JsonProperty("tenantScope")
    public String tenantScope() {
        return tenantScope;
    }

    @JsonProperty("recentTurns")
    public List<Turn> recentTurns() {
        return recentTurns;
    }

    @JsonProperty("rollingSummary")
    public String rollingSummary() {
        return rollingSummary;
    }

    @JsonProperty("totalTurnCount")
    public int totalTurnCount() {
        return totalTurnCount;
    }

    @JsonProperty("createdAt")
    public Instant createdAt() {
        return createdAt;
    }

    @JsonProperty("lastActiveAt")
    public Instant lastActiveAt() {
        return lastActiveAt;
    }

    @JsonProperty("summaryUpdatedAt")
    public Instant summaryUpdatedAt() {
        return summaryUpdatedAt;
    }

    @Override
    public String toString() {
        return "ConversationContext{"
                + "conversationId='"
                + conversationId
                + '\''
                + ", recentTurns="
                + (recentTurns == null ? 0 : recentTurns.size())
                + ", hasSummary="
                + (rollingSummary != null)
                + ", totalTurnCount="
                + totalTurnCount
                + '}';
    }

    /** 单 turn 记录。state ≠ OK 的 turn 不会进 ConversationContext (G3 抗污染)。 */
    public record Turn(
            @JsonProperty("userQuery") String userQuery,
            @JsonProperty("botAnswer") String botAnswer,
            @JsonProperty("citedChunkIds") List<Long> citedChunkIds,
            @JsonProperty("state") StateHint state,
            @JsonProperty("at") Instant at) {
        public Turn {
            Objects.requireNonNull(userQuery, "userQuery 不能为空");
            Objects.requireNonNull(botAnswer, "botAnswer 不能为空");
            Objects.requireNonNull(state, "state 不能为空");
            citedChunkIds = citedChunkIds == null ? List.of() : List.copyOf(citedChunkIds);
        }
    }
}
