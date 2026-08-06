package com.xxx.ragdoc.application.chat.harness;

/** PR-5: 单次 Harness 包装的调用描述 (不可变)。 */
public record ComponentInvocation(
        String caseId,
        String runId,
        HarnessComponentType componentType,
        String componentName,
        String componentVersion,
        int callIndex,
        InvocationContext context) {

    public ComponentInvocation {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("ComponentInvocation.caseId 必填");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("ComponentInvocation.runId 必填");
        }
        if (componentType == null) {
            throw new IllegalArgumentException("ComponentInvocation.componentType 必填");
        }
        if (componentName == null || componentName.isBlank()) {
            throw new IllegalArgumentException("ComponentInvocation.componentName 必填");
        }
        if (componentVersion == null || componentVersion.isBlank()) {
            throw new IllegalArgumentException("ComponentInvocation.componentVersion 必填");
        }
        if (callIndex < 0) {
            throw new IllegalArgumentException("ComponentInvocation.callIndex >= 0");
        }
        if (context == null) {
            throw new IllegalArgumentException("ComponentInvocation.context 必填");
        }
    }
}
