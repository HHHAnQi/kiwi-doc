package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 闲聊执行器：不访问知识库，仅使用安全固定系统上下文。 */
@Component
@RequiredArgsConstructor
public class DirectChatPipeline implements ChatPipeline {
    private final ChatClient chatClient;

    @Override
    public PipelineType type() {
        return PipelineType.DIRECT_CHAT;
    }

    @Override
    public ChatResult execute(ChatCommand command, ChatExecutionContext context) {
        try {
            String answer = chatClient.chat(command.query(), List.of("你是企业知识助手。仅进行简短礼貌闲聊，不声称访问了知识库或执行了工具。"));
            if (answer == null || answer.isBlank()) throw new IllegalStateException("empty direct chat answer");
            return ChatResult.of(StateHint.OK, answer, context.traceId());
        } catch (Exception ex) {
            return ChatResult.of(StateHint.LLM_DEGRADED, "您好，我是企业知识助手。", context.traceId());
        }
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command, ChatExecutionContext context) {
        return chatClient.chatStream(command.query(), List.of("你是企业知识助手。仅进行简短礼貌闲聊。"))
                .map(delta -> (ChatStreamEvent) new ChatStreamEvent.DeltaEvent(delta))
                .concatWith(Flux.just(new ChatStreamEvent.DoneEvent(context.traceId().value(), StateHint.OK.name())))
                .onErrorResume(ex -> Flux.just(new ChatStreamEvent.DoneEvent(context.traceId().value(), StateHint.LLM_DEGRADED.name())));
    }
}
