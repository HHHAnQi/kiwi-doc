package com.xxx.ragdoc.interfaces.rest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-SMOKE-5: REST 请求 DTO 字段双向兼容契约。
 *
 * <p>项目全局 Jackson 命名策略为 SNAKE_CASE(application.yml), 外部契约要求传 snake_case 键 ({@code doc_id}/{@code
 * top_k}/{@code trace_id}/{@code corrected_answer})。但调用方常误用 camelCase({@code docId}/{@code
 * topK}...), 反序列化时静默丢字段 → 在 doc 789 烟测中表现为 "docId 过滤看似失效, expr=null 下推到 Milvus"(根因记录于 P3-A 烟测日志)。
 *
 * <p>通过 {@code @JsonAlias} 兼容 camelCase 别名后, 两种命名都应能注入字段。本测试防回归。
 *
 * <p>本测试构造的 ObjectMapper 显式启用 SNAKE_CASE, 复现生产环境行为。
 */
@DisplayName("F-SMOKE-5: 请求 DTO JSON 双向命名兼容")
class RequestDtoNamingContractTest {

    private final ObjectMapper mapper =
            new ObjectMapper()
                    .setPropertyNamingStrategy(
                            com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

    @Test
    @DisplayName("ChatRequest: snake_case 键正确反序列化")
    void chatRequestSnakeCase() throws Exception {
        String json =
                """
                {"query":"q","doc_id":789,"top_k":5,"source":"seata","version":"1","language":"zh"}
                """;
        ChatRequest req = mapper.readValue(json, ChatRequest.class);
        assertThat(req.docId()).isEqualTo(789L);
        assertThat(req.topK()).isEqualTo(5);
        assertThat(req.source()).isEqualTo("seata");
    }

    @Test
    @DisplayName("ChatRequest: camelCase 键(@JsonAlias)也能反序列化(防静默丢字段)")
    void chatRequestCamelCaseAlias() throws Exception {
        // 历史 bug 现场: 传 docId/topK 时曾静默为 null → docId 过滤看似失效
        String json =
                """
                {"query":"q","docId":789,"topK":5,"source":"seata"}
                """;
        ChatRequest req = mapper.readValue(json, ChatRequest.class);
        assertThat(req.docId())
                .as("camelCase docId 必须被 @JsonAlias 接住, 否则 docId 过滤会静默失效")
                .isEqualTo(789L);
        assertThat(req.topK()).isEqualTo(5);
    }

    @Test
    @DisplayName("FeedbackRequest: snake_case 键正确反序列化")
    void feedbackRequestSnakeCase() throws Exception {
        String json =
                """
                {"trace_id":"abc","rating":"like","corrected_answer":"x","comment":"y"}
                """;
        FeedbackRequest req = mapper.readValue(json, FeedbackRequest.class);
        assertThat(req.traceId()).isEqualTo("abc");
        assertThat(req.correctedAnswer()).isEqualTo("x");
    }

    @Test
    @DisplayName("FeedbackRequest: camelCase 键(@JsonAlias)也能反序列化")
    void feedbackRequestCamelCaseAlias() throws Exception {
        String json =
                """
                {"traceId":"abc","rating":"like","correctedAnswer":"x"}
                """;
        FeedbackRequest req = mapper.readValue(json, FeedbackRequest.class);
        assertThat(req.traceId()).isEqualTo("abc");
        assertThat(req.correctedAnswer()).isEqualTo("x");
    }
}
