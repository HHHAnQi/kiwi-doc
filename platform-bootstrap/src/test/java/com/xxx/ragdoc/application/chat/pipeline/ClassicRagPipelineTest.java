package com.xxx.ragdoc.application.chat.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.shared.ChatMode;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-2 / EMS-PR2: {@link ClassicRagPipeline} 委托测试 — 确保改造后真正路由到 ChatService 既有同步
 * 与 SSE 链路, 没有"二套实现"行为偏移, 也没有 Evidence / stateHint / Trace 字段丢失。
 */
@DisplayName("ClassicRagPipeline - PR-2 委托既有 ChatService")
class ClassicRagPipelineTest {

    private static final TraceId TID = new TraceId("classic-trace-1");
    private static final Principal PRINCIPAL =
            new Principal("tenant-A", "user-1", Set.of(), "tok");

    private ChatService chatService;
    private ClassicRagPipeline pipeline;

    @BeforeEach
    void setup() {
        AuthContext.set(PRINCIPAL);
        chatService = mock(ChatService.class);
        pipeline = new ClassicRagPipeline(chatService);
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("type() 为 CLASSIC_RAG")
    void typeIsClassicRag() {
        assertThat(pipeline.type()).isEqualTo(PipelineType.CLASSIC_RAG);
    }

    @Test
    @DisplayName("execute → 委托 ChatService.chat(cmd, traceId, conversationId), 结果原样回传")
    void executeDelegatesToSync() {
        ChatCommand cmd = new ChatCommand("测试", null, 5, null, null, null, "conv-1");
        ChatResult stub = ChatResult.of(StateHint.OK, "答案", TID);
        when(chatService.chat(eq(cmd), eq(TID), eq("conv-1"))).thenReturn(stub);

        ChatExecutionContext ctx =
                new ChatExecutionContext(
                        "req-1", PRINCIPAL, ChatMode.RAG, PipelineType.CLASSIC_RAG, TID, ExecutionPolicy.defaults());

        ChatResult r = pipeline.execute(cmd, ctx);

        assertThat(r).isSameAs(stub);
        verify(chatService, times(1)).chat(eq(cmd), eq(TID), eq("conv-1"));
    }

    @Test
    @DisplayName("conversationId 为 null 时也走 ChatService (老 stateless 路径)")
    void executeNullConversationId() {
        ChatCommand cmd = new ChatCommand("测试", null, 5);
        when(chatService.chat(eq(cmd), eq(TID), any())).thenReturn(ChatResult.of(StateHint.OK, "x", TID));

        ChatExecutionContext ctx =
                new ChatExecutionContext(
                        "req-1", PRINCIPAL, ChatMode.AUTO, PipelineType.CLASSIC_RAG, TID, ExecutionPolicy.defaults());

        pipeline.execute(cmd, ctx);

        verify(chatService, times(1)).chat(eq(cmd), eq(TID), any());
    }

    @Test
    @DisplayName("stream → 委托 ChatService.chatStream(cmd, traceId), Flux 原样回传")
    void streamDelegates() {
        ChatCommand cmd = new ChatCommand("测试", null, 5);
        ChatStreamEvent head = new ChatStreamEvent.DoneEvent(TID.value(), StateHint.OK.name());
        when(chatService.chatStream(eq(cmd), eq(TID)))
                .thenReturn(reactor.core.publisher.Flux.just(head));

        ChatExecutionContext ctx =
                new ChatExecutionContext(
                        "req-1", PRINCIPAL, ChatMode.AUTO, PipelineType.CLASSIC_RAG, TID, ExecutionPolicy.defaults());

        java.util.List<ChatStreamEvent> events =
                pipeline.stream(cmd, ctx).collectList().block();

        assertThat(events).hasSize(1);
        verify(chatService, never()).chat(any(), any(), any());
        verify(chatService, times(1)).chatStream(eq(cmd), eq(TID));
    }
}
