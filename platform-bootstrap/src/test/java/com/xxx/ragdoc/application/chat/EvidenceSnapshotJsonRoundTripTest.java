package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-1 / EMS-PR1: {@link EvidenceSnapshot} JSON 序列化/反序列化往返。
 *
 * <p>这是 trace→evidence 还原链路的核心不变量: {@code JpaChatTracesRepository} 把 snapshot 写成 JSON 字符串存进
 * chat_traces.evidence_snapshot, 评测/调试用 {@code findEvidenceByTraceId} 反序列化拿回。 任何 Jackson record
 * 处理错配都意味着 trace 失去了 "由 traceId 还原完整 Retrieval→Rerank→Context" 能力。
 *
 * <p>不依赖数据库 — 直接调 {@link ObjectMapper}, 与生产 {@code JpaChatTracesRepository} 共享同款 Jackson。
 *
 * <p>放在 platform-bootstrap 而非 platform-common 是因为 Jackson 依赖在 bootstrap 测试 classpath (经
 * spring-boot-starter-test) 才完整; common 层 Jackson 仅 compileOnly。
 */
@DisplayName("EvidenceSnapshot JSON 往返 (Jackson record 支持)")
class EvidenceSnapshotJsonRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("snapshot 序列化再反序列化: 三段 + rerankState 完整无损")
    void roundTripPreservesAllSegments() throws Exception {
        Evidence e1 =
                Evidence.of(
                        "tenant-A",
                        1L,
                        10L,
                        "v1",
                        "正文A",
                        0.9,
                        null,
                        "retriever",
                        Map.of("page", 1));
        Evidence e2 =
                Evidence.of("tenant-A", 1L, 11L, "v1", "正文B", null, 0.88, "reranker", Map.of());
        Evidence e3 =
                Evidence.of(
                        "tenant-A",
                        1L,
                        10L,
                        "v1",
                        "正文A长上下文",
                        null,
                        0.9,
                        "context",
                        Map.of("page", 2));
        EvidenceSnapshot original =
                new EvidenceSnapshot(List.of(e1), List.of(e2), List.of(e3), "applied");

        String json = mapper.writeValueAsString(original);
        EvidenceSnapshot restored = mapper.readValue(json, EvidenceSnapshot.class);

        assertThat(restored.rerankState()).isEqualTo("applied");
        assertThat(restored.initialRetrieval()).hasSize(1);
        assertThat(restored.postRerank()).hasSize(1);
        assertThat(restored.finalContext()).hasSize(1);
        Evidence restoredContext = restored.finalContext().get(0);
        assertThat(restoredContext.tenantId()).isEqualTo("tenant-A");
        assertThat(restoredContext.chunkId()).isEqualTo(10L);
        assertThat(restoredContext.contentHash()).isEqualTo(e3.contentHash());
        assertThat(restoredContext.evidenceId()).isEqualTo(e3.evidenceId());
        assertThat(restoredContext.metadata()).containsEntry("page", 2);
        assertThat(restored.finalContext()).isUnmodifiable();
    }

    @Test
    @DisplayName("空 snapshot 往返: 仍可恢复为空三段")
    void emptySnapshotRoundTrip() throws Exception {
        EvidenceSnapshot empty = EvidenceSnapshot.empty();
        String json = mapper.writeValueAsString(empty);

        EvidenceSnapshot restored = mapper.readValue(json, EvidenceSnapshot.class);

        assertThat(restored.initialRetrieval()).isEmpty();
        assertThat(restored.postRerank()).isEmpty();
        assertThat(restored.finalContext()).isEmpty();
    }
}
