package com.xxx.ragdoc.application.chat.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.ChatMode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PR-3.3: {@link TargetedRagPipeline} 把 RouterDecision.filters→ChatCommand 的映射测试 + 委托 ChatService
 * 测试 + 无 filter 降级测试。
 */
@DisplayName("TargetedRagPipeline - PR-3.3")
class TargetedRagPipelineTest {

    private static final TraceId TID = new TraceId("targeted-trace");
    private static final Principal PRINCIPAL = new Principal("tenant-A", "user-1", Set.of(), "tok");

    private ChatService chatService;
    private TargetedRagPipeline pipeline;

    @BeforeEach
    void setup() {
        AuthContext.set(PRINCIPAL);
        chatService = mock(ChatService.class);
        pipeline = new TargetedRagPipeline(chatService);
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    private ChatExecutionContext ctxWithFilters(Map<String, Object> filters) {
        RouterDecision d =
                new RouterDecision(
                        TaskIntent.NUMERIC_OR_VERSION,
                        ExecutionStrategy.TARGETED_RAG,
                        List.of(),
                        filters,
                        0.9,
                        "VERSION_LOOKUP");
        return new ChatExecutionContext(
                "req-1",
                PRINCIPAL,
                ChatMode.AUTO,
                PipelineType.TARGETED_RAG,
                TID,
                ExecutionPolicy.defaults(),
                d);
    }

    private ChatExecutionContext ctxNoFilter() {
        return new ChatExecutionContext(
                "req-1",
                PRINCIPAL,
                ChatMode.RAG,
                PipelineType.TARGETED_RAG,
                TID,
                ExecutionPolicy.defaults());
    }

    @Nested
    @DisplayName("applyTargetedFilters 映射规则")
    class Mapping {

        @Test
        @DisplayName("filters.versions[0] → cmd.version")
        void versionApplied() {
            ChatCommand orig = new ChatCommand("v2.3 新增接口", null, 5);
            ChatCommand targeted =
                    TargetedRagPipeline.applyTargetedFilters(
                            orig, ctxWithFilters(Map.of("versions", List.of("v2.3"))));
            assertThat(targeted.version()).isEqualTo("v2.3");
            assertThat(targeted.query()).isEqualTo("v2.3 新增接口"); // query 原文保留
            assertThat(targeted.source()).isNull();
        }

        @Test
        @DisplayName("filters.products[0] → cmd.source")
        void productApplied() {
            ChatCommand orig = new ChatCommand("Nacos 健康检查在哪一节", null, 5);
            ChatCommand targeted =
                    TargetedRagPipeline.applyTargetedFilters(
                            orig, ctxWithFilters(Map.of("products", List.of("Nacos"))));
            assertThat(targeted.source()).isEqualTo("Nacos");
            assertThat(targeted.version()).isNull();
        }

        @Test
        @DisplayName("用户显式 cmd.source/version 不被 Router 覆盖")
        void userExplicitPriority() {
            ChatCommand orig = new ChatCommand("v1.0 文档", null, 5, "Dubbo", "v1.0", null, null);
            ChatCommand targeted =
                    TargetedRagPipeline.applyTargetedFilters(
                            orig,
                            ctxWithFilters(
                                    Map.of(
                                            "versions",
                                            List.of("v9.9"),
                                            "products",
                                            List.of("Sentinel"))));
            assertThat(targeted.version()).isEqualTo("v1.0");
            assertThat(targeted.source()).isEqualTo("Dubbo");
        }

        @Test
        @DisplayName("无 version/product filter → 等价回退 (orig 返回)")
        void noFilterNoOp() {
            ChatCommand orig = new ChatCommand("错误码 10086 怎么解决", null, 5);
            // filters 只有 errorCodes 没有 versions / products
            ChatCommand targeted =
                    TargetedRagPipeline.applyTargetedFilters(
                            orig, ctxWithFilters(Map.of("errorCodes", List.of("10086"))));
            assertThat(targeted).isSameAs(orig);
        }

        @Test
        @DisplayName("RouterDecision 为占位 (router disabled) → 直接返回 orig")
        void routerDisabledNoOp() {
            ChatCommand orig = new ChatCommand("v2.0 配置", null, 5);
            ChatCommand targeted = TargetedRagPipeline.applyTargetedFilters(orig, ctxNoFilter());
            assertThat(targeted).isSameAs(orig);
        }

        @Test
        @DisplayName("filters 为 null / 空集合 → 安全不抛, 返回 orig")
        void emptyNullFiltersSafe() {
            ChatCommand orig = new ChatCommand("v2.0 配置", null, 5);
            ChatCommand targeted =
                    TargetedRagPipeline.applyTargetedFilters(orig, ctxWithFilters(Map.of()));
            assertThat(targeted).isSameAs(orig);
        }
    }

    @Nested
    @DisplayName("execute/stream 委托 ChatService")
    class Delegation {

        @Test
        @DisplayName("execute 把映射后的 cmd (含 version) 传给 ChatService")
        void executePassesTargetedCommand() {
            ChatCommand orig = new ChatCommand("v2.5.1 新增功能", null, 5);
            ChatResult stub = ChatResult.of(StateHint.OK, "答案", TID);
            when(chatService.chat(any(), eq(TID), any())).thenReturn(stub);

            ChatResult r =
                    pipeline.execute(orig, ctxWithFilters(Map.of("versions", List.of("v2.5.1"))));

            assertThat(r).isSameAs(stub);
            org.mockito.ArgumentCaptor<ChatCommand> captor =
                    org.mockito.ArgumentCaptor.forClass(ChatCommand.class);
            verify(chatService, times(1)).chat(captor.capture(), eq(TID), any());
            assertThat(captor.getValue().version()).isEqualTo("v2.5.1");
        }

        @Test
        @DisplayName("stream 同样把映射后的 cmd 传给 ChatService.chatStream")
        void streamPassesTargetedCommand() {
            ChatCommand orig = new ChatCommand("Nacos 健康检查哪一节", null, 5);
            ChatStreamEvent head = new ChatStreamEvent.DoneEvent(TID.value(), StateHint.OK.name());
            when(chatService.chatStream(any(), eq(TID), any()))
                    .thenReturn(reactor.core.publisher.Flux.just(head));

            pipeline.stream(orig, ctxWithFilters(Map.of("products", List.of("Nacos"))))
                    .collectList()
                    .block();

            org.mockito.ArgumentCaptor<ChatCommand> captor =
                    org.mockito.ArgumentCaptor.forClass(ChatCommand.class);
            verify(chatService, times(1)).chatStream(captor.capture(), eq(TID), any());
            assertThat(captor.getValue().source()).isEqualTo("Nacos");
        }
    }

    @Test
    @DisplayName("type() = TARGETED_RAG")
    void typeIsTargeted() {
        assertThat(pipeline.type()).isEqualTo(PipelineType.TARGETED_RAG);
    }
}
