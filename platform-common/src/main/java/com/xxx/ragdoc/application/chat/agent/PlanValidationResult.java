package com.xxx.ragdoc.application.chat.agent;

import java.util.List;

/**
 * PR-6a / EMS-PR6 §4.2: PlanValidator 输出。合法 → errors 空 + 拓扑序; 非法 → errors + 拓扑序为空。
 *
 * <p>{@link #throwIfInvalid()} 让调用方直接抛 {@link InvalidAgentPlanException} 而不必写 if。
 */
public record PlanValidationResult(
        boolean valid, List<PlanValidationError> errors, List<String> topologicalStepOrder) {

    public PlanValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        topologicalStepOrder =
                topologicalStepOrder == null ? List.of() : List.copyOf(topologicalStepOrder);
        // 不变量: 合法时 errors 必空且拓扑序非空; 非法时 errors 非空且拓扑序空
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("PlanValidationResult.valid=true 但 errors 非空");
        }
        if (!valid) {
            topologicalStepOrder = List.of();
        }
    }

    public void throwIfInvalid() {
        if (!valid) throw new InvalidAgentPlanException(errors);
    }

    public record PlanValidationError(String code, String stepId, String safeMessage) {
        public PlanValidationError {
            if (code == null || code.isBlank()) code = "UNKNOWN";
            if (stepId == null) stepId = "";
            if (safeMessage == null) safeMessage = "";
        }
    }

    public static class InvalidAgentPlanException extends RuntimeException {
        public final List<PlanValidationError> errors;

        public InvalidAgentPlanException(List<PlanValidationError> errors) {
            super("agent plan 校验失败: " + (errors == null ? 0 : errors.size()) + " 个错误");
            this.errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
