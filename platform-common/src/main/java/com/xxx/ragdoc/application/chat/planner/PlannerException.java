package com.xxx.ragdoc.application.chat.planner;

/**
 * PR-7a / EMS-PR7 §4.5: Planner 生成阶段失败 (Provider timeout / JSON 解析失败 / 模型错误)。
 *
 * <p>{@code provider失败语义}:
 *
 * <ul>
 *   <li>LIVE/Rule Provider: 视具体 cause; Pipeline 决策是否 SYSTEM_FAILED 或回退
 *   <li>REPLAY: Fixture 缺失/不匹配 → 严格失败关闭 (Revision §11: Fixture 缺失时<b>不</b>回退 LIVE)
 * </ul>
 *
 * <p>reasonCode 短代码便于 Trace 与 Metrics 分类。
 */
public class PlannerException extends RuntimeException {

    public enum Reason {
        INVALID_JSON,
        SCHEMA_VIOLATION,
        TIMEOUT,
        PROVIDER_ERROR,
        FIXTURE_UNAVAILABLE,
        FIXTURE_CONFLICT
    }

    public final Reason reason;

    public PlannerException(Reason reason, String message) {
        super(message);
        this.reason = reason == null ? Reason.PROVIDER_ERROR : reason;
    }

    public PlannerException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason == null ? Reason.PROVIDER_ERROR : reason;
    }
}
