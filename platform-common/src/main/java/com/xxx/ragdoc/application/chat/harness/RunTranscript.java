package com.xxx.ragdoc.application.chat.harness;

import java.util.List;
import java.util.Map;

/**
 * PR-5: 一次 Run 的轨迹摘要。可作为测试产物 (内存对象 / 临时文件), <b>不</b> 持久化到生产数据库。
 *
 * <p>事件已按 {@link TranscriptEvent#sequence} 排序; safeMetadata 不含敏感字段。
 */
public record RunTranscript(
        String runId,
        String caseId,
        String startedAtIso,
        String completedAtIso,
        String finalStatus,
        HarnessMode mode,
        List<TranscriptEvent> events,
        Map<String, String> safeMetadata) {

    public RunTranscript {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("RunTranscript.runId 必填");
        }
        events = events == null ? List.of() : List.copyOf(events);
        safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
        if (mode == null) mode = HarnessMode.LIVE;
    }
}
