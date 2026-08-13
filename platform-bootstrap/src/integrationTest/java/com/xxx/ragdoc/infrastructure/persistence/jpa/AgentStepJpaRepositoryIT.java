package com.xxx.ragdoc.infrastructure.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentRunRecord;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentStepRecord;
import com.xxx.ragdoc.application.chat.agent.AgentStepRepository.AgentStepUpdate;
import com.xxx.ragdoc.application.chat.agent.AgentStepStatus;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.AgentStepJpaRepository;
import java.time.Instant;
import java.util.List;
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
 * PR-6b.3 MySQL IT: agent_step + V14 migration + FK RESTRICT + UNIQUE + CAS。
 *
 * <p><b>Docker 不可用时本机无法运行</b>; CI 执行。本机报错属 Testcontainers 初始化报错, 见 PR-6b 报告"未运行"标记。
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AgentStepRepositoryImpl.class, AgentRunRepositoryImpl.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AgentStepJpaRepositoryIT {

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

    @Autowired private AgentStepRepositoryImpl stepRepo;
    @Autowired private AgentStepJpaRepository stepJpa;
    @Autowired private AgentRunRepositoryImpl runRepo;
    @Autowired private TestEntityManager em;

    private AgentRunRecord setupRun(String runId) {
        AgentRunRecord run =
                new AgentRunRecord(
                        runId,
                        "req-1",
                        "tA",
                        "u1",
                        "COMPARISON",
                        AgentRunStatus.RECEIVED,
                        "p1",
                        "v1",
                        "fakehash",
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
        runRepo.create(run);
        em.flush();
        return run;
    }

    private AgentStepRecord newPending(String runId, String stepId, int seq) {
        return new AgentStepRecord(
                runId,
                stepId,
                seq,
                "semantic_search",
                "v1",
                null,
                "inputhash64charxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
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

    @Test
    @DisplayName("V14 迁移成功: agent_step 表可达, INSERT + SELECT")
    void v14Migration() {
        setupRun("r1");
        AgentStepRecord step = newPending("r1", "s1", 0);
        AgentStepRecord saved = stepRepo.create(step);
        em.flush();
        em.clear();
        assertThat(saved.stepId()).isEqualTo("s1");
        assertThat(saved.status()).isEqualTo(AgentStepStatus.PENDING);
        assertThat(saved.version()).isZero();
    }

    @Test
    @DisplayName("FK RESTRICT: 删除 parent agent_run 时应抛 ConstraintViolation (Step 仍引用)")
    void fkRestrictsDelete() {
        setupRun("r2");
        stepRepo.create(newPending("r2", "s1", 0));
        em.flush();
        em.clear();
        // 直接 SQL DELETE agent_run 行 — 应被 FK 拦截
        assertThatThrownBy(
                        () -> {
                            em.getEntityManager()
                                    .createNativeQuery("DELETE FROM agent_run WHERE run_id = 'r2'")
                                    .executeUpdate();
                            em.flush();
                        })
                .isInstanceOfAny(Exception.class);
    }

    @Test
    @DisplayName("UNIQUE(run_id, step_id): 同 run 同 step_id 二次插入 → DataIntegrityViolationException")
    void uniqueRunStepId() {
        setupRun("r3");
        stepRepo.create(newPending("r3", "s1", 0));
        em.flush();
        assertThatThrownBy(
                        () -> {
                            stepRepo.create(newPending("r3", "s1", 99)); // 同 step_id 但不同 seq
                            em.flush();
                        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("UNIQUE(run_id, step_sequence): 同 run 同 sequence 二次插入 → 拒绝")
    void uniqueRunStepSequence() {
        setupRun("r4");
        stepRepo.create(newPending("r4", "s1", 0));
        em.flush();
        assertThatThrownBy(
                        () -> {
                            stepRepo.create(newPending("r4", "s2", 0)); // 同 sequence=0 但不同 step_id
                            em.flush();
                        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("transition CAS: PENDING→RESERVED→RUNNING→SUCCEEDED 三段合法版本推进")
    void transitionChains() {
        setupRun("r5");
        stepRepo.create(newPending("r5", "s1", 0));
        em.flush();
        em.clear();
        boolean r1 =
                stepRepo.transition(
                        "r5",
                        "s1",
                        0L,
                        Set.of(AgentStepStatus.PENDING),
                        AgentStepStatus.RESERVED,
                        AgentStepUpdate.empty());
        boolean r2 =
                stepRepo.transition(
                        "r5",
                        "s1",
                        1L,
                        Set.of(AgentStepStatus.RESERVED),
                        AgentStepStatus.RUNNING,
                        AgentStepUpdate.empty());
        boolean r3 =
                stepRepo.transition(
                        "r5",
                        "s1",
                        2L,
                        Set.of(AgentStepStatus.RUNNING),
                        AgentStepStatus.SUCCEEDED,
                        AgentStepUpdate.empty());
        assertThat(r1).isTrue();
        assertThat(r2).isTrue();
        assertThat(r3).isTrue();
        em.clear();
        AgentStepRecord refreshed = stepRepo.findByRunIdAndStepId("r5", "s1").orElseThrow();
        assertThat(refreshed.status()).isEqualTo(AgentStepStatus.SUCCEEDED);
        assertThat(refreshed.version()).isEqualTo(3L);
    }

    @Test
    @DisplayName("transition CAS 冲突: expectedVersion 错 → affected=0")
    void transitionCasConflict() {
        setupRun("r6");
        stepRepo.create(newPending("r6", "s1", 0));
        em.flush();
        em.clear();
        boolean ok =
                stepRepo.transition(
                        "r6",
                        "s1",
                        999L,
                        Set.of(AgentStepStatus.PENDING),
                        AgentStepStatus.RESERVED,
                        AgentStepUpdate.empty());
        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("终态保护: SUCCEEDED 后任何 transition CAS 都失败 (DB 层 status IN 集合不含已终态)")
    void terminalStateProtected() {
        setupRun("r7");
        stepRepo.create(newPending("r7", "s1", 0));
        em.flush();
        em.clear();
        // 走完到 SUCCEEDED
        stepRepo.transition(
                "r7",
                "s1",
                0L,
                Set.of(AgentStepStatus.PENDING),
                AgentStepStatus.RESERVED,
                AgentStepUpdate.empty());
        stepRepo.transition(
                "r7",
                "s1",
                1L,
                Set.of(AgentStepStatus.RESERVED),
                AgentStepStatus.RUNNING,
                AgentStepUpdate.empty());
        stepRepo.transition(
                "r7",
                "s1",
                2L,
                Set.of(AgentStepStatus.RUNNING),
                AgentStepStatus.SUCCEEDED,
                AgentStepUpdate.empty());
        em.flush();
        em.clear();
        // 再尝试 RUNNING→FAILED_TERMINAL expectedStatus=RUNNING (但实际状态已 SUCCEEDED) → 失败
        boolean ok =
                stepRepo.transition(
                        "r7",
                        "s1",
                        3L,
                        Set.of(AgentStepStatus.RUNNING),
                        AgentStepStatus.FAILED_TERMINAL,
                        AgentStepUpdate.empty());
        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("findByRunId 按 step_sequence 排序")
    void findByRunIdOrdered() {
        setupRun("r8");
        stepRepo.create(newPending("r8", "b", 1));
        stepRepo.create(newPending("r8", "a", 0));
        em.flush();
        em.clear();
        List<AgentStepRecord> list = stepRepo.findByRunId("r8");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).stepId()).isEqualTo("a"); // seq=0
        assertThat(list.get(1).stepId()).isEqualTo("b"); // seq=1
    }
}
