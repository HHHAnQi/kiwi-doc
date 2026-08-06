package com.xxx.ragdoc.application.chat.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-3.2 退出门禁: 在 100 条标注集 {@code eval/router/router_cases.jsonl} 上评估
 * {@link RuleBasedTaskRouter} 的 Intent Accuracy / Strategy Accuracy /
 * 低置信回退是否正确生效 / 不产生非法 strategy。
 *
 * <p>本测试在编译 platform-common 时即可跑(只读 classpath 资源), 不依赖 backend / LLM / Docker,
 * 适合 CI 作为 PR-3 退出门禁。失败即认为 PR-3 未达标。
 *
 * <p>当前规则版预期: high strategy accuracy on FACT/NUMERIC/UNANSWERABLE/COMPARISON;
 * MULTI_HOP / ENTITY_LOOKUP 在边角 case 可能误判 → 通过断言阈值而非 100%, 真实记录当前限制。
 */
@DisplayName("Router Eval on 100-case dataset (PR-3 退出门禁)")
class RuleBasedTaskRouterDatasetTest {

    private static final double STRATEGY_ACCURACY_THRESHOLD = 0.85;
    private static final double INTENT_ACCURACY_THRESHOLD = 0.85;

    private final RuleBasedTaskRouter router = new RuleBasedTaskRouter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("100 条数据集: Strategy Accuracy ≥ 0.85 且无非法 strategy")
    void strategyAccuracyOn100Cases() throws Exception {
        List<CaseRow> rows = loadDataset();
        assertThat(rows).hasSize(100);

        int intentHit = 0;
        int strategyHit = 0;
        List<String> mismatches = new ArrayList<>();
        for (CaseRow row : rows) {
            RouterDecision d = router.route(row.question);
            assertThat(d.strategy()).isNotNull();
            // 非法 strategy 防御: 必须是枚举内的合法值
            assertThat(d.strategy().name()).matches("CLASSIC_RAG|TARGETED_RAG|FIXED_WORKFLOW|REFUSE");
            if (d.intent().name().equals(row.intent)) intentHit++;
            if (d.strategy().name().equals(row.expectedStrategy)) {
                strategyHit++;
            } else {
                mismatches.add(
                        String.format(
                                "caseId=%s q='%s' expected=%s/%s actual=%s/%s reason=%s",
                                row.caseId,
                                row.question,
                                row.intent,
                                row.expectedStrategy,
                                d.intent(),
                                d.strategy(),
                                d.reasonCode()));
            }
        }
        double strategyAcc = (double) strategyHit / rows.size();
        double intentAcc = (double) intentHit / rows.size();
        System.out.println("STRATEGY_ACCURACY=" + strategyAcc + " INTENT_ACCURACY=" + intentAcc);
        mismatches.stream().limit(25).forEach(s -> System.out.println("  MISMATCH " + s));

        assertThat(strategyAcc)
                .as("Strategy Accuracy (%.3f) < threshold %.2f", strategyAcc, STRATEGY_ACCURACY_THRESHOLD)
                .isGreaterThanOrEqualTo(STRATEGY_ACCURACY_THRESHOLD);
        assertThat(intentAcc)
                .as("Intent Accuracy (%.3f) < threshold %.2f", intentAcc, INTENT_ACCURACY_THRESHOLD)
                .isGreaterThanOrEqualTo(INTENT_ACCURACY_THRESHOLD);
    }

    @Test
    @DisplayName("低置信度回退: 任何 conf<0.7 的非 REFUSE 决策 strategy 必为 CLASSIC_RAG")
    void lowConfidenceFallbackInvariant() throws Exception {
        List<CaseRow> rows = loadDataset();
        int fallbackCases = 0;
        for (CaseRow row : rows) {
            RouterDecision d = router.route(row.question);
            if (d.confidence() < RuleBasedTaskRouter.LOW_CONFIDENCE_THRESHOLD
                    && d.strategy() != ExecutionStrategy.REFUSE) {
                fallbackCases++;
                assertThat(d.strategy())
                        .as("低置信度应回退 CLASSIC_RAG: case " + row.caseId)
                        .isEqualTo(ExecutionStrategy.CLASSIC_RAG);
                assertThat(d.reasonCode())
                        .as("低置信度 reasonCode 应含 LOW_CONFIDENCE_FALLBACK: case " + row.caseId)
                        .contains("LOW_CONFIDENCE_FALLBACK");
            }
        }
        System.out.println("LOW_CONFIDENCE_FALLBACK_CASES=" + fallbackCases);
        // 防止 router "声称" 全部 high confidence 来规避回退: 至少 FACT 兜底该有几条低置信
        assertThat(fallbackCases).isGreaterThan(0);
    }

    @Test
    @DisplayName("UNANSWERABLE 行必须全部 strategy=REFUSE (安全优先级)")
    void refuseAllUnanswerable() throws Exception {
        List<CaseRow> rows = loadDataset();
        for (CaseRow row : rows) {
            if (!row.intent.equals("UNANSWERABLE")) continue;
            RouterDecision d = router.route(row.question);
            assertThat(d.strategy())
                    .as("UNANSWERABLE case 应 REFUSE: " + row.caseId + " q=" + row.question)
                    .isEqualTo(ExecutionStrategy.REFUSE);
            assertThat(d.intent()).isEqualTo(TaskIntent.UNANSWERABLE);
        }
    }

    private List<CaseRow> loadDataset() throws Exception {
        List<CaseRow> out = new ArrayList<>();
        try (BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(
                                Objects.requireNonNull(
                                        getClass()
                                                .getClassLoader()
                                                .getResourceAsStream(
                                                        "eval/router/router_cases.jsonl")),
                                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                out.add(mapper.readValue(line, CaseRow.class));
            }
        }
        return out;
    }

    /** JSON 行 schema 与 eval/router/router_cases.jsonl 对齐。 */
    public static class CaseRow {
        public String caseId;
        public String question;
        public String intent;
        public String expectedStrategy;
        public List<String> entities;
        public String reason;
    }
}
