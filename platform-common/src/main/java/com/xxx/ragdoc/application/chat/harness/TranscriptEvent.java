package com.xxx.ragdoc.application.chat.harness;

/**
 * PR-5: 一个 Transcript 事件 = 一次 Run 中的可观察点。
 *
 * <p>{@link #sequence} 全 Run 内严格单调递增, 让 REPLAY 重放时顺序稳定。{@link #callId} 关联 PR-4 Tool callId
 * (TOOL 类型) 或 Router callId (ROUTER); 不存敏感原文。
 */
public record TranscriptEvent(
        long sequence,
        TranscriptEventType type,
        HarnessComponentType componentType,
        String componentName,
        String componentVersion,
        int callIndex,
        String callId,
        String replayKeyShort,
        String outcomeOrErrorCode,
        java.util.Map<String, String> safeMetadata) {

    public TranscriptEvent {
        if (type == null) {
            throw new IllegalArgumentException("TranscriptEvent.type 必填");
        }
        safeMetadata = safeMetadata == null ? java.util.Map.of() : java.util.Map.copyOf(safeMetadata);
    }
}
