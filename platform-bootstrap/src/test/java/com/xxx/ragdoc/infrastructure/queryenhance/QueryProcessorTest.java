package com.xxx.ragdoc.infrastructure.queryenhance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.QueryEnhanceProperties;
import com.xxx.ragdoc.application.chat.conversation.EnhanceResult;
import com.xxx.ragdoc.infrastructure.llm.LlmRouter;
import com.xxx.ragdoc.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Task 6: {@link QueryProcessor} 单测。
 *
 * <p>覆盖:
 *
 * <ul>
 *   <li>rewrite ok: LLM JSON 规范 → primaryQuery 返 rewrite
 *   <li>parrot-echo: rewrite == original + 无 expansion → SKIP
 *   <li>LLM call 抛异常 → FAILED fallback original
 *   <li>LLM 返 markdown 围栏 → stripToJson 容错解析
 *   <li>LLM 返非法 JSON → parse_failed fallback original
 *   <li>expansion mode: 至多 maxExpansionQueries 条扩展
 *   <li>both mode: rewrite + expansions 同时有
 *   <li>empty/null query → SKIP
 * </ul>
 *
 * <p>风格 mirror {@code QueryContextualizerTest} — Mock LlmRouter + 单独 CB registry + metrics。
 */
@DisplayName("Task 6 QueryProcessor")
class QueryProcessorTest {

    private OpenAiCompatibleLlmClient routeClient;
    private LlmRouter router;
    private RagdocMetrics metrics;
    private QueryEnhanceProperties props;
    private QueryProcessor qp;

    @BeforeEach
    void setup() throws Exception {
        routeClient = mock(OpenAiCompatibleLlmClient.class);
        router = mock(LlmRouter.class);
        when(router.getRouteClient("fallback")).thenReturn(routeClient);
        metrics = mock(RagdocMetrics.class);
        props = new QueryEnhanceProperties();
        props.setMode(QueryEnhanceProperties.Mode.REWRITE);
        qp = new QueryProcessor(router, CircuitBreakerRegistry.ofDefaults(), metrics, props);
    }

    @Test
    @DisplayName("rewrite ok: LLM 返规范 JSON → primaryQuery = rewritten")
    void rewriteOk() throws Exception {
        when(routeClient.chat(anyString(), anyList()))
                .thenReturn("{\"rewritten\": \"Dubbo 服务注册延迟配置项\"}");
        EnhanceResult r = qp.enhance("Rancher 部署 dubbo 咋样", null);
        assertThat(r.outcome()).isEqualTo("ok");
        assertThat(r.primaryQuery()).isEqualTo("Dubbo 服务注册延迟配置项");
        assertThat(r.allQueries()).containsExactly("Dubbo 服务注册延迟配置项");
    }

    @Test
    @DisplayName("parrot-echo: rewrite == original 且无 expansion → SKIP")
    void parrotEchoSkipped() throws Exception {
        when(routeClient.chat(anyString(), anyList()))
                .thenReturn("{\"rewritten\": \"原始query\"}");
        EnhanceResult r = qp.enhance("原始query", null);
        assertThat(r.outcome()).isEqualTo("skip");
        assertThat(r.primaryQuery()).isEqualTo("原始query");
    }

    @Test
    @DisplayName("LLM 抛异常 → FAILED fallback original, 不挂主流程")
    void llmExceptionReturnsFailed() throws Exception {
        when(routeClient.chat(anyString(), anyList()))
                .thenThrow(new RuntimeException("LLM 503"));
        EnhanceResult r = qp.enhance("query", null);
        assertThat(r.outcome()).isEqualTo("failed");
        assertThat(r.primaryQuery()).isEqualTo("query");
        assertThat(r.errorMessage()).contains("LLM 503");
    }

    @Test
    @DisplayName("LLM 返 markdown ```json 围栏 → stripToJson 容错解析")
    void stripMarkdownJsonFence() throws Exception {
        when(routeClient.chat(anyString(), anyList()))
                .thenReturn("```json\n{\"rewritten\": \"改写后\"}\n```");
        EnhanceResult r = qp.enhance("query", null);
        assertThat(r.outcome()).isEqualTo("ok");
        assertThat(r.primaryQuery()).isEqualTo("改写后");
    }

    @Test
    @DisplayName("LLM 返非法 JSON → parse_failed fallback original")
    void invalidJsonReturnsFailed() throws Exception {
        when(routeClient.chat(anyString(), anyList())).thenReturn("I cannot help with that.");
        EnhanceResult r = qp.enhance("query", null);
        assertThat(r.outcome()).isEqualTo("failed");
        assertThat(r.primaryQuery()).isEqualTo("query");
    }

    @Nested
    @DisplayName("mode = expansion")
    class Expansion {
        @BeforeEach
        void setMode() {
            props.setMode(QueryEnhanceProperties.Mode.EXPANSION);
            props.setMaxExpansionQueries(3);
        }

        @Test
        @DisplayName("expansion 模式: 输出 expansions 列表 (主 query 仍是原 query)")
        void expansionsListReturned() throws Exception {
            when(routeClient.chat(anyString(), anyList()))
                    .thenReturn(
                            "{\"expansions\": [\"Dubbo provider timeout\", \"Dubbo consumer timeout\", \"Dubbo 方法级 timeout\"]}");
            EnhanceResult r = qp.enhance("Dubbo 超时配置项", null);
            assertThat(r.outcome()).isEqualTo("ok");
            // rewrite 没显式给 → primaryQuery=原 query
            assertThat(r.primaryQuery()).isEqualTo("Dubbo 超时配置项");
            assertThat(r.expandedQueries()).hasSize(3);
            // allQueries = primary + 3 expansions (去重)
            assertThat(r.allQueries()).hasSize(4);
            assertThat(r.allQueries().get(0)).isEqualTo("Dubbo 超时配置项");
        }

        @Test
        @DisplayName("maxExpansionQueries 截断超出条数")
        void maxExpansionTruncated() throws Exception {
            when(routeClient.chat(anyString(), anyList()))
                    .thenReturn(
                            "{\"expansions\": [\"a\", \"b\", \"c\", \"d\", \"e\"]}");
            EnhanceResult r = qp.enhance("query", null);
            assertThat(r.expandedQueries()).hasSize(3); // 受 props.max=3 截断
        }
    }

    @Nested
    @DisplayName("mode = both")
    class Both {
        @BeforeEach
        void setMode() {
            props.setMode(QueryEnhanceProperties.Mode.BOTH);
            props.setMaxExpansionQueries(2);
        }

        @Test
        @DisplayName("both 模式: rewrite + expansions 同时有")
        void rewritePlusExpansions() throws Exception {
            when(routeClient.chat(anyString(), anyList()))
                    .thenReturn(
                            "{\"rewritten\": \"主改写\", \"expansions\": [\"扩展1\", \"扩展2\"]}");
            EnhanceResult r = qp.enhance("query", null);
            assertThat(r.outcome()).isEqualTo("ok");
            assertThat(r.primaryQuery()).isEqualTo("主改写");
            assertThat(r.expandedQueries()).containsExactly("扩展1", "扩展2");
            assertThat(r.allQueries()).containsExactly("主改写", "扩展1", "扩展2");
        }
    }

    @Test
    @DisplayName("empty query → SKIP, 不调 LLM")
    void emptyQuerySkipped() throws Exception {
        EnhanceResult emptyR = qp.enhance("", null);
        assertThat(emptyR.outcome()).isEqualTo("skip");
        EnhanceResult nullR = qp.enhance(null, null);
        assertThat(nullR.outcome()).isEqualTo("skip");
        verifyNoInteractions(routeClient);
    }
}
