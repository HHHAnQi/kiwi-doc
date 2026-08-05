package com.xxx.ragdoc.application.chat.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-1 / EMS-PR1: {@link Evidence} 不可变值对象核心不变量。 */
@DisplayName("Evidence - 不可变 + tenantId 强制 + id 唯一")
class EvidenceTest {

    @Test
    @DisplayName("of() 自动算 evidenceId / contentHash, 且 metadata 不可变")
    void factoryComputesIdsAndImmutableMetadata() {
        Evidence e =
                Evidence.of(
                        "tenant-A",
                        10L,
                        200L,
                        "v1",
                        "正文内容",
                        0.92,
                        null,
                        "retriever",
                        Map.of("page", 3));

        assertThat(e.evidenceId()).hasSize(64).matches("^[a-fA-F0-9]{64}$");
        assertThat(e.contentHash()).hasSize(64).matches("^[a-fA-F0-9]{64}$");
        assertThat(e.tenantId()).isEqualTo("tenant-A");
        assertThat(e.content()).isEqualTo("正文内容");
        assertThat(e.metadata()).containsEntry("page", 3);
        assertThatThrownBy(() -> e.metadata().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("tenantId 不允许空 (服务端硬约束, 防 LLM/客户端偷传)")
    void tenantIdIsRequiredFromPrincipal() {
        assertThatThrownBy(
                        () -> Evidence.of(null, 1L, 1L, "v", "x", 0.1, null, "retriever", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> Evidence.of("  ", 1L, 1L, "v", "x", 0.1, null, "retriever", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("documentId / chunkId 必填 (必须能映射回真实 Document 与 Chunk)")
    void docAndChunkIdRequired() {
        assertThatThrownBy(
                        () ->
                                Evidence.of(
                                        "t", null, 1L, "v", "x", 0.1, null, "retriever", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                Evidence.of(
                                        "t", 1L, null, "v", "x", 0.1, null, "retriever", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("同 tenant/doc/chunk/内容 → 同 evidenceId; 不同 tenant → 不同 evidenceId")
    void evidenceIdStableAndTenantScopable() {
        Evidence a = Evidence.of("tenant-A", 1L, 1L, "v", "内容", 0.5, null, "retriever", Map.of());
        Evidence b = Evidence.of("tenant-A", 1L, 1L, "v", "内容", 0.5, null, "retriever", Map.of());
        Evidence otherTenant =
                Evidence.of("tenant-B", 1L, 1L, "v", "内容", 0.5, null, "retriever", Map.of());

        assertThat(a.evidenceId()).isEqualTo(b.evidenceId()); // 同实体 → 同 ID
        assertThat(a.evidenceId()).isNotEqualTo(otherTenant.evidenceId()); // tenantIds 不同 → 不同
        // content 一样 → contentHash 一样, 跨 tenant 也一致 (去重键不变)
        assertThat(a.contentHash()).isEqualTo(otherTenant.contentHash());
    }
}
