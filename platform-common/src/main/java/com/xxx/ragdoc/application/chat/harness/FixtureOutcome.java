package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * PR-5 / EMS-PR5: Harness 内部对组件调用结果的统一分类 Outcome。
 *
 * <p>用 {@link Outcome} 而非 PR-4 的 ToolStatus, 让 Harness 与 ToolStatus 解耦
 * (未来 PLANNER / SUFFICIENCY_JUDGE 没有 ToolStatus 概念)。
 *
 * <p>映射 ToolStatus → Outcome 由 ToolHarnessAdapter 处理 (不是本契约层职责)。
 */
public final class FixtureOutcome {
    public enum Outcome {
        SUCCESS,
        EMPTY_RESULT,
        ERROR,
        TIMEOUT,
        CANCELLED,
        PERMISSION_DENIED
    }

    private FixtureOutcome() {}

    /** Fixture outcome record; outcome/error 用于后续 REPLAY 重塑返回类型。 */
    public record OutcomeResult(Outcome outcome, JsonNode structuredResponse, FixtureError error) {
        public OutcomeResult {
            if (outcome == null) {
                throw new IllegalArgumentException("OutcomeResult.outcome 必填");
            }
        }

        public static OutcomeResult success(JsonNode response) {
            return new OutcomeResult(Outcome.SUCCESS, response, null);
        }

        public static OutcomeResult empty() {
            return new OutcomeResult(Outcome.EMPTY_RESULT, null, null);
        }

        public static OutcomeResult error(FixtureError err) {
            return switch (err.category()) {
                case TIMEOUT -> new OutcomeResult(Outcome.TIMEOUT, null, err);
                case PERMISSION -> new OutcomeResult(Outcome.PERMISSION_DENIED, null, err);
                case CANCELLED -> new OutcomeResult(Outcome.CANCELLED, null, err);
                default -> new OutcomeResult(Outcome.ERROR, null, err);
            };
        }
    }
}
