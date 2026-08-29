package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.router.OnlineReasonCode;
import com.xxx.ragdoc.application.chat.router.OnlineRoute;
import com.xxx.ragdoc.domain.shared.StateHint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Prepare 之后同步/SSE 共用的派发、拒答和收尾内核。 */
@Service
@RequiredArgsConstructor
public class OnlineExecutionKernel {
    private final ChatPipelineRegistry registry;

    public ChatResult execute(ChatCommand command, OnlineExecutionContext context) {
        if (context.route() == OnlineRoute.REFUSE) return refusal(context);
        ChatResult result =
                registry.get(context.effectivePipeline())
                        .execute(command, context.toLegacyContext());
        return finalizeResult(result, context);
    }

    public Flux<ChatStreamEvent> stream(ChatCommand command, OnlineExecutionContext context) {
        if (context.route() == OnlineRoute.REFUSE) {
            return Flux.just(
                    new ChatStreamEvent.DeltaEvent(refusalMessage(context)),
                    new ChatStreamEvent.DoneEvent(
                            context.traceId().value(),
                            StateHint.REFUSED.name(),
                            context.reasonCode().name()));
        }
        return registry.get(context.effectivePipeline()).stream(command, context.toLegacyContext())
                .map(event -> enrichTerminal(event, context));
    }

    private static ChatResult finalizeResult(ChatResult result, OnlineExecutionContext context) {
        return result.withPipelineType(context.effectivePipeline())
                .withReasonCode(reasonFor(result.stateHint(), context).name());
    }

    private static ChatResult refusal(OnlineExecutionContext context) {
        return ChatResult.of(StateHint.REFUSED, refusalMessage(context), context.traceId())
                .withReasonCode(context.reasonCode().name());
    }

    private static ChatStreamEvent enrichTerminal(
            ChatStreamEvent event, OnlineExecutionContext context) {
        if (!(event instanceof ChatStreamEvent.DoneEvent done)) return event;
        if (done.reasonCode() != null && !done.reasonCode().isBlank()) return done;
        StateHint hint;
        try {
            hint = StateHint.valueOf(done.stateHint());
        } catch (RuntimeException ex) {
            return new ChatStreamEvent.DoneEvent(
                    done.traceId(), done.stateHint(), OnlineReasonCode.INTERNAL_ERROR.name());
        }
        return new ChatStreamEvent.DoneEvent(
                done.traceId(), done.stateHint(), reasonFor(hint, context).name());
    }

    private static OnlineReasonCode reasonFor(StateHint hint, OnlineExecutionContext context) {
        return switch (hint) {
            case OK -> context.reasonCode();
            case REFUSED -> context.reasonCode();
            case EMPTY_KB -> OnlineReasonCode.EMPTY_KB;
            case NO_RECALL -> OnlineReasonCode.NO_RECALL;
            case LLM_DEGRADED -> OnlineReasonCode.LLM_UNAVAILABLE;
            case VERIFY_FAILED -> OnlineReasonCode.VERIFICATION_FAILED;
        };
    }

    private static String refusalMessage(OnlineExecutionContext context) {
        return switch (context.reasonCode()) {
            case REFUSE_PROMPT_INJECTION -> "请求包含不安全指令，已拒绝执行。";
            case REFUSE_OUT_OF_SCOPE -> "该请求超出企业知识助手允许执行的能力范围。";
            case REFUSE_OUT_OF_DOMAIN -> "该问题不属于当前企业知识库服务范围。";
            case REFUSE_EMPTY_QUERY -> "请输入需要咨询的问题。";
            default -> "根据安全与能力边界，本次请求无法处理。";
        };
    }
}
