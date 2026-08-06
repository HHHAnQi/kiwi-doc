package com.xxx.ragdoc.application.chat.harness;

/** PR-5: Transcript 事件类型, 用于单一 Run 的组件调用顺序追踪。 */
public enum TranscriptEventType {
    RUN_STARTED,
    COMPONENT_STARTED,
    COMPONENT_COMPLETED,
    COMPONENT_FAILED,
    FIXTURE_RECORDED,
    FIXTURE_REPLAYED,
    RUN_COMPLETED,
    RUN_FAILED
}
