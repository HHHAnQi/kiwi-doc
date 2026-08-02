package com.xxx.ragdoc.parser.domain;

/**
 * parser-service 任务状态机(对应 parse_tasks.status ENUM)。
 *
 * <p>迁移规则(spec §3.3):
 *
 * <ul>
 *   <li>{@link #PENDING}: 队列中, worker 可抢(visible_at ≤ now)
 *   <li>{@link #RUNNING}: worker 持有, 解析中
 *   <li>{@link #PARSED}: 成功, 终态
 *   <li>{@link #FAILED}: 本次失败但 retry_count &lt; max_retries, 暂态(将回 PENDING)
 *   <li>{@link #CANCELLED}: retry_count 达 max_retries, DLQ 终态
 * </ul>
 *
 * <p>迁移 invariant(代码层守护): 见 {@code ParseTaskService}.
 */
public enum ParseTaskStatus {
    PENDING,
    RUNNING,
    PARSED,
    FAILED,
    CANCELLED;

    /** 业务终态: 不再调度。 */
    public boolean isTerminal() {
        return this == PARSED || this == CANCELLED;
    }
}
