package com.xxx.ragdoc.infrastructure.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.AgentStepRecord;
import com.xxx.ragdoc.application.chat.agent.AgentStepRepository.AgentStepUpdate;
import com.xxx.ragdoc.application.chat.agent.AgentStepStatus;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentStepEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.AgentStepJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PR-6a.2: AgentStepRepositoryImpl 单测 (mock JPA; DB 集成留给 CI / Docker MySQL)。
 *
 * <p>覆盖:
 * <li>- create 只接受 PENDING
 * <li>- findByRunId 按 step_sequence ASC
 * <li>- CAS transition (正常 / 失败 / 未知状态 fail-closed)
 * <li>- evidenceIds JSON 序列化 (null vs 空 list vs 非空)
 */
@DisplayName("AgentStepRepositoryImpl - PR-6a.2 CAS + JSON round-trip")
class AgentStepRepositoryImplTest {

    private AgentStepJpaRepository jpa;
    private AgentStepRepositoryImpl repo;

    @BeforeEach
    void setup() {
        jpa = org.mockito.Mockito.mock(AgentStepJpaRepository.class);
        repo = new AgentStepRepositoryImpl(jpa, new ObjectMapper());
    }

    private AgentStepRecord newPendingStep(String runId, String stepId, int seq) {
        return new AgentStepRecord(
                runId,
                stepId,
                seq,
                "semantic_search",
                "v1",
                null,
                "input-hash-64char-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                AgentStepStatus.PENDING,
                0,
                List.of(),
                null,
                null,
                false,
                false,
                false,
                null,
                null,
                Instant.now(),
                Instant.now(),
                0);
    }

    private AgentStepEntity savedEntity(
            String runId, String stepId, int seq, String status, long version) {
        AgentStepEntity e = new AgentStepEntity();
        e.setId(1L);
        e.setRunId(runId);
        e.setStepId(stepId);
        e.setStepSequence(seq);
        e.setToolName("semantic_search");
        e.setToolVersion("v1");
        e.setInputHash("input-hash-64char-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        e.setStatus(status);
        e.setResultCount(0);
        e.setVersion(version);
        return e;
    }

    @Nested
    @DisplayName("create + findByRunId")
    class CreateAndFind {

        @Test
        @DisplayName("create(PENDING) → Entity 持久化; 返回 record 状态=PENDING version=0")
        void createPendingOnly() {
            AgentStepRecord step = newPendingStep("run-1", "step-1", 1);
            when(jpa.save(any())).thenReturn(savedEntity("run-1", "step-1", 1, "PENDING", 0L));

            AgentStepRecord out = repo.create(step);

            assertThat(out.runId()).isEqualTo("run-1");
            assertThat(out.stepId()).isEqualTo("step-1");
            assertThat(out.status()).isEqualTo(AgentStepStatus.PENDING);
            assertThat(out.version()).isEqualTo(0L);
        }

        @Test
        @DisplayName("create(非 PENDING) → IllegalArgumentException (新建 Step 必须从 PENDING 开始)")
        void createNonPendingRejected() {
            AgentStepRecord running =
                    new AgentStepRecord(
                            "run-1",
                            "step-1",
                            1,
                            "semantic_search",
                            "v1",
                            null,
                            "hash",
                            AgentStepStatus.RUNNING,
                            0,
                            List.of(),
                            null,
                            null,
                            false,
                            false,
                            false,
                            null,
                            null,
                            Instant.now(),
                            Instant.now(),
                            0);

            assertThatThrownBy(() -> repo.create(running))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("create(空 evidenceIds list) → evidence_ids_json = NULL (不写空数组)")
        void createEmptyEvidenceIsNull() {
            AgentStepRecord step = newPendingStep("run-1", "step-1", 1);
            when(jpa.save(any())).thenReturn(savedEntity("run-1", "step-1", 1, "PENDING", 0L));

            repo.create(step);

            org.mockito.ArgumentCaptor<AgentStepEntity> cap =
                    org.mockito.ArgumentCaptor.forClass(AgentStepEntity.class);
            verify(jpa).save(cap.capture());
            assertThat(cap.getValue().getEvidenceIdsJson()).isNull();
        }

        @Test
        @DisplayName("findByRunIdAndStepId: 命中 → 返回 record")
        void findByRunIdAndStepId() {
            when(jpa.findByRunIdAndStepId("run-1", "step-1"))
                    .thenReturn(Optional.of(savedEntity("run-1", "step-1", 1, "SUCCEEDED", 3L)));

            Optional<AgentStepRecord> out = repo.findByRunIdAndStepId("run-1", "step-1");

            assertThat(out).isPresent();
            assertThat(out.get().status()).isEqualTo(AgentStepStatus.SUCCEEDED);
            assertThat(out.get().version()).isEqualTo(3L);
        }

        @Test
        @DisplayName("findByRunId: 按 step_sequence ASC 排序 (调用 JPA 层 derived query)")
        void findByRunIdOrdered() {
            when(jpa.findByRunIdOrderByStepSequenceAsc("run-1"))
                    .thenReturn(
                            List.of(
                                    savedEntity("run-1", "a", 1, "SUCCEEDED", 2L),
                                    savedEntity("run-1", "b", 2, "RUNNING", 1L)));

            List<AgentStepRecord> out = repo.findByRunId("run-1");

            assertThat(out).hasSize(2);
            assertThat(out.get(0).stepSequence()).isEqualTo(1);
            assertThat(out.get(1).stepSequence()).isEqualTo(2);
            // 确认排序委托给 JPA
            verify(jpa).findByRunIdOrderByStepSequenceAsc("run-1");
        }

        @Test
        @DisplayName("toRecord: 未知 status → IllegalStateException fail-closed")
        void unknownStatusFailClosed() {
            AgentStepEntity e = savedEntity("run-1", "step-1", 1, "GHOST", 0L);
            when(jpa.findByRunIdAndStepId("run-1", "step-1")).thenReturn(Optional.of(e));

            assertThatThrownBy(() -> repo.findByRunIdAndStepId("run-1", "step-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未知状态");
        }

        @Test
        @DisplayName("toRecord: evidenceIdsJson round-trip (非空 list 正确反序列化)")
        void evidenceIdsRoundTrip() {
            AgentStepEntity e = savedEntity("run-1", "step-1", 1, "SUCCEEDED", 1L);
            e.setEvidenceIdsJson("[\"ev-1\",\"ev-2\"]");
            e.setResultCount(2);
            when(jpa.findByRunIdAndStepId("run-1", "step-1")).thenReturn(Optional.of(e));

            Optional<AgentStepRecord> out = repo.findByRunIdAndStepId("run-1", "step-1");

            assertThat(out).isPresent();
            assertThat(out.get().evidenceIds()).containsExactly("ev-1", "ev-2");
            assertThat(out.get().resultCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("CAS transition")
    class CasTransition {

        @Test
        @DisplayName("正常 CAS 链: PENDING→RESERVED→RUNNING→SUCCEEDED 每步 affected=1 → true")
        void casChainSuccess() {
            // 15 个 JPA 参数: anyLong() 匹配原始 long, 其余 any() 匹配对象 (含 null)
            stubTransitionReturn(1);

            AgentStepUpdate running =
                    new AgentStepUpdate(
                            "call-1",
                            0,
                            List.of(),
                            100L,
                            null,
                            false,
                            false,
                            false,
                            Instant.now(),
                            Instant.now());

            boolean ok1 =
                    repo.transition(
                            "run-1",
                            "step-1",
                            0L,
                            Set.of(AgentStepStatus.PENDING),
                            AgentStepStatus.RESERVED,
                            AgentStepUpdate.empty());
            boolean ok2 =
                    repo.transition(
                            "run-1",
                            "step-1",
                            1L,
                            Set.of(AgentStepStatus.RESERVED),
                            AgentStepStatus.RUNNING,
                            running);
            boolean ok3 =
                    repo.transition(
                            "run-1",
                            "step-1",
                            2L,
                            Set.of(AgentStepStatus.RUNNING),
                            AgentStepStatus.SUCCEEDED,
                            running);

            assertThat(ok1).isTrue();
            assertThat(ok2).isTrue();
            assertThat(ok3).isTrue();
        }

        @Test
        @DisplayName("CAS 失败: jpa.transition=0 → false (version / status 不匹配)")
        void casConflict() {
            stubTransitionReturn(0);

            boolean ok =
                    repo.transition(
                            "run-1",
                            "step-1",
                            999L,
                            Set.of(AgentStepStatus.PENDING),
                            AgentStepStatus.RESERVED,
                            AgentStepUpdate.empty());

            assertThat(ok).isFalse();
        }

        @Test
        @DisplayName("CAS: 空 evidenceIds → 传 NULL 给 COALESCE (不覆盖原值)")
        void casEmptyEvidenceNulled() {
            stubTransitionReturn(1);

            repo.transition(
                    "run-1",
                    "step-1",
                    0L,
                    Set.of(AgentStepStatus.PENDING),
                    AgentStepStatus.RESERVED,
                    AgentStepUpdate.empty());

            // 第 8 个参数 (evidenceIdsJson) 空 list 被映射为 null; 用 nullable 兼容
            verify(jpa)
                    .transition(
                            any(),
                            any(),
                            anyLong(),
                            any(),
                            any(),
                            any(),
                            any(),
                            nullable(String.class),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        @DisplayName("CAS: 非空 evidenceIds → 序列化为 JSON 字符串")
        void casNonEmptyEvidenceJson() {
            stubTransitionReturn(1);

            AgentStepUpdate upd =
                    new AgentStepUpdate(
                            "call-1",
                            2,
                            List.of("ev-1", "ev-2"),
                            150L,
                            null,
                            false,
                            false,
                            false,
                            Instant.now(),
                            Instant.now());

            repo.transition(
                    "run-1",
                    "step-1",
                    0L,
                    Set.of(AgentStepStatus.RUNNING),
                    AgentStepStatus.SUCCEEDED,
                    upd);

            // JSON 字符串必须包含两个 evidence IDs
            org.mockito.ArgumentCaptor<String> evCap =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(jpa)
                    .transition(
                            any(),
                            any(),
                            anyLong(),
                            any(),
                            any(),
                            any(),
                            any(),
                            evCap.capture(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
            assertThat(evCap.getValue()).contains("ev-1").contains("ev-2");
        }
    }

    // 统一 stub transition 返回值: 15 个参数 (runId, stepId, long, 集合, target, 再 10 个 update 字段)
    @SuppressWarnings("unchecked")
    private void stubTransitionReturn(int affected) {
        when(jpa.transition(
                        any(),
                        any(),
                        anyLong(),
                        any(java.util.Collection.class),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(affected);
    }
}
