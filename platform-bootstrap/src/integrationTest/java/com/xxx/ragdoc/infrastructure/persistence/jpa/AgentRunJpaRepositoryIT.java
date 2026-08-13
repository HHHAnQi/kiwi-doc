package com.xxx.ragdoc.infrastructure.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentRunRecord;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentRunEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.AgentRunJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PR-6b.3 MySQL IT: agent_run + V13 migration + CAS + JSON 序列化 + 唯一约束。
 *
 * <p><b>Docker 不可用时本机无法运行</b>, CI 执行; PR-6b 报告中标记 &quot;未运行&quot;。
 *
 * <p>覆盖 EMS-PR6 §14.1 必填项: V13 可迁移 / JSON round-trip / transition CAS / settleRunStep 合并 CAS /
 * updateEvidenceSummary / findByTenantId desc / 唯一约束 (run_id PK)。
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AgentRunRepositoryImpl.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AgentRunJpaRepositoryIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("ragdoc_it")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }

    @Autowired private AgentRunRepositoryImpl repo;
    @Autowired private AgentRunJpaRepository jpa;
    @Autowired private TestEntityManager em;
    private final ObjectMapper mapper = new ObjectMapper();

    private AgentRunRecord newRun(String runId) {
        return new AgentRunRecord(
                runId,
                "req-1",
                "tA",
                "u1",
                "COMPARISON",
                AgentRunStatus.RECEIVED,
                "p1",
                "v1",
                "fakehash64charxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                "{\"planId\":\"p1\"}",
                AgentBudget.pr6Default(),
                AgentBudgetReservation.zero(),
                AgentUsage.zero(),
                List.of(),
                0,
                null,
                "rv",
                "tsv",
                "iv1",
                "LIVE",
                null,
                null,
                0);
    }

    @Test
    @DisplayName("V13 迁移成功: agent_run 表存在, INSERT + SELECT 通过")
    void v13MigrationCreatesTable() {
        AgentRunRecord saved = repo.create(newRun("r-it-1"));
        em.flush();
        em.clear();
        assertThat(saved.runId()).isEqualTo("r-it-1");

        Optional<AgentRunRecord> reloaded = repo.findByRunId("r-it-1");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().status()).isEqualTo(AgentRunStatus.RECEIVED);
    }

    @Test
    @DisplayName("JSON round-trip: budget/reservation/usage 完整恢复")
    void jsonColumnsRoundTrip() {
        repo.create(newRun("r-it-2"));
        em.flush();
        em.clear();
        AgentRunRecord r = repo.findByRunId("r-it-2").orElseThrow();
        assertThat(r.budget().maxSteps()).isEqualTo(3);
        assertThat(r.usage().usedSteps()).isZero();
        assertThat(r.reservation().reservedToolCalls()).isZero();
        assertThat(r.planJson()).contains("planId");
    }

    @Test
    @DisplayName("唯一约束: 同 run_id 二次插入 → DataIntegrityViolationException")
    void uniqueRunIdConstraint() {
        repo.create(newRun("r-it-dup"));
        em.flush();
        assertThatThrownBy(
                        () -> {
                            repo.create(newRun("r-it-dup"));
                            em.flush();
                        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("transition CAS: RECEIVED→ROUTED 成功 (affected=1)")
    void transitionCas() {
        repo.create(newRun("r-it-3"));
        em.flush();
        em.clear();
        boolean ok =
                repo.transition(
                        "r-it-3",
                        0L,
                        Set.of(AgentRunStatus.RECEIVED),
                        AgentRunStatus.ROUTED,
                        "ROUTED",
                        AgentUsage.zero(),
                        AgentBudgetReservation.zero());
        assertThat(ok).isTrue();
        em.clear();
        AgentRunRecord refreshed = repo.findByRunId("r-it-3").orElseThrow();
        assertThat(refreshed.status()).isEqualTo(AgentRunStatus.ROUTED);
        assertThat(refreshed.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("transition CAS 冲突: expectedVersion 错 → affected=0")
    void transitionCasConflict() {
        repo.create(newRun("r-it-4"));
        em.flush();
        em.clear();
        boolean ok =
                repo.transition(
                        "r-it-4",
                        999L, // 错版本
                        Set.of(AgentRunStatus.RECEIVED),
                        AgentRunStatus.ROUTED,
                        "ROUTED",
                        AgentUsage.zero(),
                        AgentBudgetReservation.zero());
        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("settleRunStep 合并 CAS: usage+reservation+evidenceIds 一次推进")
    void settleRunStepCas() {
        repo.create(newRun("r-it-5"));
        em.flush();
        em.clear();
        boolean ok =
                repo.settleRunStep(
                        "r-it-5",
                        0L,
                        Set.of(AgentRunStatus.RECEIVED),
                        AgentUsage.zero().incStep().incRealToolCall(),
                        new AgentBudgetReservation(0, 0, 0, 0, 0, java.math.BigDecimal.ZERO),
                        List.of("ev-1", "ev-2"),
                        2);
        assertThat(ok).isTrue();
        em.clear();
        AgentRunRecord r = repo.findByRunId("r-it-5").orElseThrow();
        assertThat(r.usage().usedToolCalls()).isEqualTo(1);
        assertThat(r.evidenceCount()).isEqualTo(2);
        assertThat(r.evidenceIds()).containsExactly("ev-1", "ev-2");
    }

    @Test
    @DisplayName("findByTenantId desc 排序")
    void findByTenantIdDesc() throws Exception {
        AgentRunRecord a = newRun("r-a");
        Thread.sleep(20);
        AgentRunRecord b = newRun("r-b");
        repo.create(a);
        em.flush();
        repo.create(b);
        em.flush();
        em.clear();
        List<AgentRunRecord> list = repo.findByTenantId("tA", 10);
        assertThat(list).hasSize(2);
        // 最新 (r-b) 应排在前
        assertThat(list.get(0).runId()).isEqualTo("r-b");
    }

    @Test
    @DisplayName("updateEvidenceSummary: 空列表 → evidence_ids_json NULL (不写空数组)")
    void evidenceSummaryEmptyList() {
        repo.create(newRun("r-it-7"));
        em.flush();
        em.clear();
        boolean ok =
                repo.updateEvidenceSummary(
                        "r-it-7", 0L, Set.of(AgentRunStatus.RECEIVED), List.of(), 0);
        assertThat(ok).isTrue();
        em.clear();
        AgentRunEntity ent = jpa.findById("r-it-7").orElseThrow();
        assertThat(ent.getEvidenceIdsJson()).isNull();
        assertThat(ent.getEvidenceCount()).isZero();
    }
}
