package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.ChatService;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.domain.shared.PipelineType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * PR-2 / EMS-PR2: Classic RAG pipeline, 等价于改造前的 ChatService 同步 + SSE 链路。
 *
 * <p><b>提取策略</b> (满足"不一次性重写 ChatService"):
 *
 * <ol>
 *   <li>本类不搬运业务逻辑, 只做最小委托 — {@link #execute}/{@link #stream} 直接调
 *       {@link ChatService#chat(ChatCommand, com.xxx.ragdoc.domain.shared.TraceId, String)}
 *       与 {@link ChatService#chatStream(ChatCommand, com.xxx.ragdoc.domain.shared.TraceId)}。
 *   <li>所有 Retrieve / Rerank / Context / LLM / Citation / Trace / Evidence Snapshot / SSE 单终态
 *       全部由 ChatService 既有实现提供, PR-0 / PR-1 行为零变化。
 *   <li>{@link ChatService} 的公共方法保留 — 既有单测 (ChatServiceTest, RetrieveServiceTest) 与
 *       其他调用方继续工作, 不在本 PR 触及。
 * </ol>
 *
 * <p>{@link ChatExecutionContext} 在 PR-2 中只被记录到 Trace / 日志, 业务字段
 * (query / topK / source 等) 仍走 {@link ChatCommand}。principal equality 与 tenantId 一致性
 * 由 AuthFilter → AuthContext → ChatService 既有访问链路保证, 本 pipeline 不重新解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassicRagPipeline implements ChatPipeline {

    /** Pipeline 版本, 进 Trace metadata; 改造本 pipeline 内部行为时 bump。 */
    public static final String PIPELINE_VERSION = "classic-rag-v1";

    private final ChatService chatService;

    @Override
    public PipelineType type() {
        return PipelineType.CLASSIC_RAG;
    }

    @Override
    public ChatResult execute(ChatCommand command, ChatExecutionContext context) {
        log.info(
                "pipeline.classic.execute request_id={}, trace_id={}, mode={}, conversation_id={}",
                context.requestId(),
                context.traceId().value(),
                context.requestedMode(),
                safeConvId(command));
        return chatService.chat(command, context.traceId(), command.conversationId());
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command, ChatExecutionContext context) {
        log.info(
                "pipeline.classic.stream request_id={}, trace_id={}, mode={}",
                context.requestId(),
                context.traceId().value(),
                context.requestedMode());
        return chatService.chatStream(command, context.traceId());
    }

    private static String safeConvId(ChatCommand command) {
        return command.conversationId() == null || command.conversationId().isBlank()
                ? "none"
                : command.conversationId();
    }
}
