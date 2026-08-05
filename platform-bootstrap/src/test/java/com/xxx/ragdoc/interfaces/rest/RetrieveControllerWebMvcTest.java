package com.xxx.ragdoc.interfaces.rest;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.RerankProperties;
import com.xxx.ragdoc.application.chat.RetrieveService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@link RetrieveController} WebMvc IT: 仅起 Spring MVC 切片, mock 掉 RetrieveService 与三个
 * properties bean; 不需要 MySQL/Milvus/Redis/Docker, 可独立跑通。
 *
 * <p>验证:
 *
 * <ul>
 *   <li>路由可达 (200), 不串到 /api/v1/chat
 *   <li>请求/响应 DTO 字段存在 (snake_case + score 字段未丢)
 *   <li>Service 收到的 ChatCommand 字段映射正确
 *   <li>query 必填校验生效 (400)
 * </ul>
 */
@WebMvcTest(controllers = RetrieveController.class)
@Import(com.xxx.ragdoc.interfaces.rest.error.GlobalExceptionHandler.class)
@TestPropertySource(
        properties = {
            "llm.model=glm-4-plus-test",
            "embedding.model=BAAI/bge-m3-test"
        })
class RetrieveControllerWebMvcTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private RetrieveService retrieveService;
    @MockBean private RerankProperties rerankProperties;

    @Nested
    @DisplayName("POST /api/v1/retrieve")
    class HappyPath {

        @Test
        @DisplayName("200 + items 含 chunk_id/doc_id/score 字段, 不进 LLM")
        void returns200AndExposesScore() throws Exception {
            RetrieveService.Citation cit =
                    new RetrieveService.Citation(
                            1330L, 55L, 2, "snippet…", "llm context full", 0.87f, List.of("dubbo"));
            RetrieveService.RetrieveResult result =
                    new RetrieveService.RetrieveResult(List.of(cit), "applied", 0.91f, 0.87f);
            // Task 5: RetrieveController 改走 retrieve(cmd, mode) 双参; mock 双参让默认 mode=null 也命中
            when(retrieveService.retrieve(any(ChatCommand.class), any(), any())).thenReturn(result);
            when(rerankProperties.getModel()).thenReturn("BAAI/bge-reranker-v2-m3");
            when(rerankProperties.isEnabled()).thenReturn(true);

            ResultActions r =
                    mockMvc.perform(
                                    post("/api/v1/retrieve")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"query\":\"dubbo 延迟连接\",\"top_k\":5}"))
                            .andExpect(status().isOk());

            String json = r.andReturn().getResponse().getContentAsString();
            // snake_case + 关键字段 (score 被 ChatResult 丢弃, 这里必须保留)
            for (String needle :
                    new String[] {
                        "\"chunk_id\":1330",
                        "\"doc_id\":55",
                        "\"score\":0.87",
                        "\"rerank_state\":\"applied\"",
                        "\"model_version\":\"glm-4-plus-test\"",
                        "\"embedding_version\":\"BAAI/bge-m3-test\"",
                        "\"rerank_model\":\"BAAI/bge-reranker-v2-m3\"",
                        "\"rerank_enabled\":true"
                    }) {
                if (!json.contains(needle)) {
                    throw new AssertionError("missing `" + needle + "` in response: " + json);
                }
            }

            // ChatCommand 字段映射: query + topK 透传
            verify(retrieveService)
                    .retrieve(
                            argThat(
                                    c ->
                                            c.query().equals("dubbo 延迟连接")
                                                    && c.topK() == 5
                                                    && c.docId() == null),
                            any() /* Task 5: mode override (default/null) */,
                            any() /* Task 6: enhance override (default/null) */);
        }
    }

    @Nested
    @DisplayName("请求校验")
    class Validation {

        @Test
        @DisplayName("空 query → 400")
        void blankQuery400() throws Exception {
            mockMvc.perform(
                            post("/api/v1/retrieve")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"query\":\"\",\"top_k\":5}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(retrieveService);
        }

        @Test
        @DisplayName("top_k 越界 → 400")
        void topKOutOfRange400() throws Exception {
            mockMvc.perform(
                            post("/api/v1/retrieve")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"query\":\"x\",\"top_k\":99}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(retrieveService);
        }
    }
}
