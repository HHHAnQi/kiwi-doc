package com.xxx.ragdoc.application.chat.pipeline;

/**
 * PR-2 / EMS-PR2: 单请求执行策略入口 (统一同步 + SSE)。
 *
 * <p><b>本 PR 范围</b>: 只把现有 WebClient/Provider timeout 与流式/cancel 信号归一到这里, 不新增 Agent Budget 字段 (不引入
 * maxSteps / maxToolCalls / maxPlannerCalls / 成本上限)。 Agent Budget 由后续 PR (PR-6) 在 AgentState/Budget
 * 中接入。
 *
 * <p>{@link #streamingAllowed} 让 Orchestrator / Pipeline 在拒绝流式请求 (如 future Agent 不支持 SSE) 时一致失败;
 * PR-2 Classic 全程支持。
 *
 * <p>{@link #chatTimeoutMillis} 来自 {@code rag.chat.timeout-ms} 等既有配置 (PR-2 不绑定 @Transactional
 * timeout, 见 PR-2 设计 §7)。0 表示沿用 provider 各段 timeout, 不引入总闸。
 */
public record ExecutionPolicy(
        boolean streamingAllowed, long chatTimeoutMillis, long providerTimeoutMillis) {

    /** PR-2 默认策略: 允许流式, 沿用既有 provider timeout, 不引入新总闸。 */
    public static ExecutionPolicy defaults() {
        return new ExecutionPolicy(true, 0L, 0L);
    }
}
