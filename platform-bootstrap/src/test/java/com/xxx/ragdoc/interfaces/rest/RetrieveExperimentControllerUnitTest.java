package com.xxx.ragdoc.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.RerankProperties;
import com.xxx.ragdoc.application.chat.RetrieveService;
import com.xxx.ragdoc.application.document.port.Retriever;
import com.xxx.ragdoc.common.exception.BaseException;
import com.xxx.ragdoc.domain.auth.Principal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Task 5: {@link RetrieveExperimentController} 单元测试 — 不用 MockMvc (filter chain 走 ThreadLocal
 * 跨线程, 单测不模拟), 直接调 controller 方法。AuthContext 用 ReflectionTestUtils 模拟。
 */
@DisplayName("Task 5 RetrieveExperimentController")
class RetrieveExperimentControllerUnitTest {

    private RetrieveExperimentController controller;
    private RetrieveService retrieveService;
    private RerankProperties rerankProperties;

    @BeforeEach
    void setup() {
        retrieveService = mock(RetrieveService.class);
        rerankProperties = new RerankProperties();
        controller = new RetrieveExperimentController(retrieveService, rerankProperties);
        ReflectionTestUtils.setField(controller, "llmModel", "test-llm");
        ReflectionTestUtils.setField(controller, "embeddingModel", "test-emb");
    }

    @AfterEach
    void clearTl() {
        AuthContext.clear();
    }

    private void loginAdmin(boolean admin) {
        AuthContext.set(
                new Principal(
                        "default",
                        admin ? "admin" : "userA",
                        admin
                                ? Set.of("role:default", "role:user", "role:admin")
                                : Set.of("role:default", "role:user"),
                        admin ? "admin-token" : "user-token"));
    }

    @Nested
    @DisplayName("守门: 仅 role:admin 可调")
    class Guard {

        @Test
        @DisplayName("admin + 合法 mode=hybrid → 200 (调 service, mode override 透传)")
        void adminCanCallWithHybridMode() {
            loginAdmin(true);
            com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest req =
                    new com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest(
                            "test query", null, 5, null, null, null, "hybrid");
            when(retrieveService.retrieve(any(), any()))
                    .thenReturn(new RetrieveService.RetrieveResult(List.of(), "not_enabled", 0f, 0f));

            controller.experiment(req);

            verify(retrieveService).retrieve(any(), eq(Retriever.Mode.HYBRID));
        }

        @Test
        @DisplayName("admin + 合法 mode=dense → 200 (mode也存在 override)")
        void adminCanCallWithDenseMode() {
            loginAdmin(true);
            com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest req =
                    new com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest(
                            "test query", null, 5, null, null, null, "dense");
            when(retrieveService.retrieve(any(), any()))
                    .thenReturn(new RetrieveService.RetrieveResult(List.of(), "not_enabled", 0f, 0f));

            controller.experiment(req);

            verify(retrieveService).retrieve(any(), eq(Retriever.Mode.DENSE));
        }

        @Test
        @DisplayName("非 admin → BaseException (语义: UNAUTHORIZED, 防生产 Milvus 被刷)")
        void nonAdminRejected() {
            loginAdmin(false);
            com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest req =
                    new com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest(
                            "test query", null, 5, null, null, null, "hybrid");

            assertThatThrownBy(() -> controller.experiment(req))
                    .isInstanceOf(BaseException.class);
            verifyNoInteractions(retrieveService);
        }
    }

    @Nested
    @DisplayName("mode 校验: 必填且合法")
    class ModeValidation {

        @Test
        @DisplayName("mode 缺失 (null) → BaseException")
        void missingModeRejected() {
            loginAdmin(true);
            com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest req =
                    new com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest(
                            "test query", null, 5, null, null, null, null);

            assertThatThrownBy(() -> controller.experiment(req))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("mode");
            verifyNoInteractions(retrieveService);
        }

        @Test
        @DisplayName("mode 非法 (banana) → BaseException")
        void invalidModeRejected() {
            loginAdmin(true);
            com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest req =
                    new com.xxx.ragdoc.interfaces.rest.dto.RetrieveRequest(
                            "test query", null, 5, null, null, null, "banana");

            assertThatThrownBy(() -> controller.experiment(req))
                    .isInstanceOf(BaseException.class);
            verifyNoInteractions(retrieveService);
        }
    }

    @Test
    @DisplayName("parseMode helper: 合法值变 enum, 非法/null 返 null (老路径容忍)")
    void parseModeTolerant() {
        assertThat(RetrieveController.parseMode("dense")).isEqualTo(Retriever.Mode.DENSE);
        assertThat(RetrieveController.parseMode("HYBRID")).isEqualTo(Retriever.Mode.HYBRID);
        assertThat(RetrieveController.parseMode("  hybrid  ")).isEqualTo(Retriever.Mode.HYBRID);
        assertThat(RetrieveController.parseMode(null)).isNull();
        assertThat(RetrieveController.parseMode("")).isNull();
        assertThat(RetrieveController.parseMode("invalid")).isNull();
    }
}
