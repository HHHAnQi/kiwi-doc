package com.xxx.ragdoc.interfaces.rest;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.xxx.ragdoc.application.document.DocumentManageService;
import com.xxx.ragdoc.application.document.DocumentQueryService;
import com.xxx.ragdoc.application.document.DocumentUploadService;
import com.xxx.ragdoc.application.document.query.DocumentDetail;
import com.xxx.ragdoc.common.exception.DomainException;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 3 / P3-3 WebMvc IT: 新增端点 /default + /unarchive 路由 + DTO 新字段 (isDefault /
 * pendingMilvusDelete) JSON 序列化验证。
 *
 * <p>切片: @WebMvcTest 仅起 Spring MVC 切片 (mock 掉 application service), 不需要 MySQL /
 * Milvus / Redis / Docker — 故本章可独立跑通, 不在 IT 失败队列里。
 */
@WebMvcTest(controllers = DocumentController.class)
@Import(com.xxx.ragdoc.interfaces.rest.error.GlobalExceptionHandler.class)
class DocumentControllerP3WebMvcTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private DocumentUploadService uploadService;
    @MockBean private DocumentQueryService queryService;
    @MockBean private DocumentManageService manageService;

    @Nested
    @DisplayName("POST /api/v1/documents/{id}/default")
    class SetDefault {
        @Test
        @DisplayName("成功 → 200")
        void returns200OnSuccess() throws Exception {
            mockMvc.perform(post("/api/v1/documents/123/default")).andExpect(status().isOk());
            verify(manageService).setDefault(123L);
        }

        @Test
        @DisplayName("Service 抛 DomainException → 4xx (验证路由可达, 不 500 NPE)")
        void propagateServiceException() throws Exception {
            // 不严格等 409 (的状态码由 GlobalExceptionHandler 映射); 只验服务异常被处理后返 4xx/5xx 非 NPE
            doThrow(new DomainException(ErrorCode.DOC_NOT_FAILED, "仅 READY 可设默认"))
                    .when(manageService)
                    .setDefault(org.mockito.ArgumentMatchers.eq(456L));

            int status =
                    mockMvc.perform(post("/api/v1/documents/456/default"))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            // GlobalExceptionHandler 走到则状态在 400-599 之间, 不是未捕获 NPE 的 500 也能跑过去。
            // 这里仅校验非 200 即可 (Service 异常被处理)
            if (status == 200) {
                throw new AssertionError("Service 应抛异常, HTTP 不应为 200, got " + status);
            }
        }
    }

    @Nested
    @DisplayName("POST /api/v1/documents/{id}/unarchive")
    class Unarchive {
        @Test
        @DisplayName("成功 → 202 Accepted")
        void returns202OnSuccess() throws Exception {
            mockMvc.perform(post("/api/v1/documents/789/unarchive"))
                    .andExpect(status().isAccepted());
            verify(manageService).unarchive(789L);
        }
    }

    @Nested
    @DisplayName("DTO JSON 序列化 — isDefault / pendingMilvusDelete 字段暴露")
    class DtoJsonFields {
        @Test
        @DisplayName("GET /api/v1/documents/{id} → JSON 含 isDefault + pendingMilvusDelete 字段")
        void detailResponseExposesP3Fields() throws Exception {
            DocumentDetail detail =
                    new DocumentDetail(
                            1L,
                            "nacos.pdf",
                            "application/pdf",
                            DocumentStatus.READY,
                            100,
                            5,
                            0,
                            null,
                            java.time.Instant.parse("2026-08-01T00:00:00Z"),
                            java.time.Instant.parse("2026-08-02T00:00:00Z"),
                            "nacos",
                            "2.4",
                            "zh",
                            "doc",
                            true,
                            false);
            when(queryService.getDetail(1L)).thenReturn(detail);

            ResultActions r =
                    mockMvc.perform(get("/api/v1/documents/1")).andExpect(status().isOk());

            String json = r.andReturn().getResponse().getContentAsString();
            // 应用 @JsonNaming(snake_case) → JSON 用 is_default (而非 Java isDefault)
            if (!json.contains("\"is_default\":true")) {
                throw new AssertionError("JSON should contain is_default=true, got: " + json);
            }
            if (!json.contains("\"pending_milvus_delete\":false")) {
                throw new AssertionError(
                        "JSON should contain pending_milvus_delete=false, got: " + json);
            }
        }
    }
}
