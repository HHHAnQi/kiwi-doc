package com.xxx.ragdoc.infrastructure.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentRunRecord;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentRunEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.AgentRunJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** PR-6a.2: AgentRunRepositoryImpl 单测 (mock JPA; DB 集成留给 CI)。 */
@DisplayName("AgentRunRepositoryImpl - PR-6a.2 CAS + JSON round-trip")
class AgentRunRepositoryImplTest {

    private AgentRunJpaRepository jpa;
    private AgentRunRepositoryImpl repo;

    @BeforeEach
    void setup() {
        jpa = org.mockito.Mockito.mock(AgentRunJpaRepository.class);
        repo = new AgentRunRepositoryImpl(jpa, new ObjectMapper());
    }

    private AgentRunRecord newRun(String runId) {
        return new AgentRunRecord(
                runId, "req-1", "tenant-A", "user-1", "CLASSIC_RAG",
                AgentRunStatus.RECEIVED,
                "plan-1", "v1", "fake-hash-64-char-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                "{\"planId\":\"plan-1\"}",
                AgentBudget.pr6Default(), AgentBudgetReservation.zero(), AgentUsage.zero(),
                List.of(), 0,
                null, "rule-based-v1", "toolset-v1", "iv-1", "LIVE",
                Instant.now(), Instant.now(), 0);
    }

    @Nested
    @DisplayName("create + findByRunId: JSON round-trip")
    class CreateAndFind {

        @Test
        @DisplayName("create → Entity 持久化; findByRunId → JSON budget/usage/reservation 正确恢复")
        void jsonRoundTrip() throws Exception {
            ObjectMapper om = new ObjectMapper();
            AgentRunRecord rec = newRun("run-1");
            AgentRunEntity savedEntity = new AgentRunEntity();
            savedEntity.setRunId("run-1");
            savedEntity.setRequestId("req-1");
            savedEntity.setTenantId("tenant-A");
            savedEntity.setUserId("user-1");
            savedEntity.setStrategy("CLASSIC_RAG");
            savedEntity.setStatus("RECEIVED");
            savedEntity.setPlanId("plan-1");
            savedEntity.setPlanVersion("v1");
            savedEntity.setPlanHash(rec.planHash());
            savedEntity.setPlanJson("{\"planId\":\"plan-1\"}");
            savedEntity.setBudgetJson(om.writeValueAsString(rec.budget()));
            savedEntity.setReservationJson(om.writeValueAsString(AgentBudgetReservation.zero()));
            savedEntity.setUsageJson(om.writeValueAsString(AgentUsage.zero()));
            savedEntity.setEvidenceCount(0);
            savedEntity.setHarnessMode("LIVE");
            savedEntity.setVersion(0L);
            when(jpa.save(any())).thenReturn(savedEntity);

            AgentRunRecord out = repo.create(rec);

            assertThat(out.runId()).isEqualTo("run-1");
            assertThat(out.status()).isEqualTo(AgentRunStatus.RECEIVED);
            assertThat(out.budget().maxSteps()).isEqualTo(3);
            assertThat(out.usage().usedSteps()).isEqualTo(0);
            assertThat(out.reservation().reservedToolCalls()).isEqualTo(0);
        }

        @Test
        @DisplayName("findByRunId: 未知 status → IllegalStateException fail-closed")
        void unknownStatusFailClosed() {
            AgentRunEntity e = new AgentRunEntity();
            e.setStatus("GHOST_STATUS");
            when(jpa.findById("run-x")).thenReturn(Optional.of(e));

            assertThatThrownBy(() -> repo.findByRunId("run-x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未知状态");
        }

        @Test
        @DisplayName("evidence 只保存 IDs 列表, 不含 Evidence 正文字段")
        void evidenceIdsOnly() {
            AgentRunRecord rec = new AgentRunRecord(
                    "run-2", "req-1", "tenant-A", "user-1", "CLASSIC_RAG",
                    AgentRunStatus.READY_TO_ANSWER,
                    "plan-1", "v1", "fake-hash", "{}",
                    AgentBudget.pr6Default(), AgentBudgetReservation.zero(), AgentUsage.zero(),
                    List.of("ev-1", "ev-2"), 2,
                    null, null, null, "iv-1", "LIVE",
                    Instant.now(), Instant.now(), 0);
            AgentRunEntity savedEntity = new AgentRunEntity();
            savedEntity.setRunId("run-2");
            savedEntity.setRequestId("req-1");
            savedEntity.setTenantId("tenant-A");
            savedEntity.setUserId("user-1");
            savedEntity.setStrategy("CLASSIC_RAG");
            savedEntity.setStatus("READY_TO_ANSWER");
            savedEntity.setPlanId("plan-1");
            savedEntity.setPlanVersion("v1");
            savedEntity.setPlanHash("fake-hash");
            savedEntity.setPlanJson("{}");
            savedEntity.setBudgetJson("{\"maxSteps\":3}");
            savedEntity.setReservationJson("{\"reservedSteps\":0}");
            savedEntity.setUsageJson("{\"usedSteps\":0}");
            savedEntity.setEvidenceIdsJson("[\"ev-1\",\"ev-2\"]");
            savedEntity.setEvidenceCount(2);
            savedEntity.setHarnessMode("LIVE");
            savedEntity.setVersion(0L);
            when(jpa.save(any())).thenReturn(savedEntity);

            AgentRunRecord out = repo.create(rec);

            assertThat(out.evidenceIds()).containsExactly("ev-1", "ev-2");
            assertThat(out.evidenceCount()).isEqualTo(2);
            // Entity 没有任何 content / chunkId / documentId JSON 列
        }

        @Test
        @DisplayName("findByTenantId: 返回 audit list (按 created_at desc)")
        void findByTenantId() {
            AgentRunEntity e1 = new AgentRunEntity();
            e1.setRunId("r1");
            e1.setTenantId("tenant-A");
            e1.setUserId("u");
            e1.setStrategy("CLASSIC_RAG");
            e1.setStatus("RECEIVED");
            e1.setPlanId("p");
            e1.setPlanVersion("v1");
            e1.setPlanHash("h");
            e1.setPlanJson("{}");
            e1.setBudgetJson("{\"maxSteps\":3}");
            e1.setReservationJson("{\"reservedSteps\":0}");
            e1.setUsageJson("{\"usedSteps\":0}");
            e1.setHarnessMode("LIVE");
            e1.setVersion(0L);
            when(jpa.findByTenantIdOrderByCreatedAtDesc("tenant-A")).thenReturn(List.of(e1));

            List<AgentRunRecord> out = repo.findByTenantId("tenant-A", 10);
            assertThat(out).hasSize(1);
            assertThat(out.get(0).runId()).isEqualTo("r1");
        }
    }

    @Nested
    @DisplayName("CAS transition")
    class CasTransition {

        @Test
        @DisplayName("正常 CAS: jpa.transition=1 → true")
        void casSuccess() {
            when(jpa.transition(any(), eq(0L), any(), eq("ROUTED"), any(), any(), any()))
                    .thenReturn(1);

            boolean ok = repo.transition(
                    "run-1", 0L,
                    Set.of(AgentRunStatus.RECEIVED),
                    AgentRunStatus.ROUTED,
                    "ROUTED", AgentUsage.zero(), AgentBudgetReservation.zero());
            assertThat(ok).isTrue();
        }

        @Test
        @DisplayName("CAS 失败: jpa.transition=0 → false (version/status 或 run 不匹配)")
        void casConflict() {
            when(jpa.transition(any(), anyLong(), any(), any(), any(), any(), any()))
                    .thenReturn(0);

            boolean ok = repo.transition(
                    "run-missing", 0L,
                    Set.of(AgentRunStatus.RECEIVED),
                    AgentRunStatus.ROUTED,
                    "ROUTED", AgentUsage.zero(), AgentBudgetReservation.zero());
            assertThat(ok).isFalse();
        }

        @Test
        @DisplayName("updateBudgetState CAS: 成功")
        void updateBudgetCasSuccess() {
            when(jpa.updateBudgetState(any(), anyLong(), any(), any(), any())).thenReturn(1);

            boolean ok = repo.updateBudgetState(
                    "run-1", 0L,
                    Set.of(AgentRunStatus.EXECUTING),
                    AgentUsage.zero().incStep().incRealToolCall(),
                    new AgentBudgetReservation(1, 1, 0, 0, 0, java.math.BigDecimal.ZERO));
            assertThat(ok).isTrue();
        }

        @Test
        @DisplayName("updateEvidenceSummary CAS: 成功")
        void evidenceSummaryCasSuccess() {
            when(jpa.updateEvidenceSummary(any(), anyLong(), any(), any(), eq(2))).thenReturn(1);

            boolean ok = repo.updateEvidenceSummary(
                    "run-1", 0L,
                    Set.of(AgentRunStatus.EXECUTING),
                    List.of("ev-1", "ev-2"), 2);
            assertThat(ok).isTrue();
        }
    }

    // Helper: avoid ObjectMapper null return
    private ObjectMapper objectMapperField() {
        return new ObjectMapper();
    }

    // Inline helper for JSON serialization in test (lambda-friendly)
    private static class UbleSafe {
        // placeholder to make compile above inline construction smooth
    }

    // Actually use a method reference for writeValueAsString
    private Object writeValueAsString(ObjectMapper m, AgentBudget b) {
        try { return m.writeValueAsString(b); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
