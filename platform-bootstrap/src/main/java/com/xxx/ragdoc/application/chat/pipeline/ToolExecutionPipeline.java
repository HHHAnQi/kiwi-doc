package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.tool.KeywordSearchTool;
import com.xxx.ragdoc.application.chat.tool.SearchInput;
import com.xxx.ragdoc.application.chat.tool.SearchOutput;
import com.xxx.ragdoc.application.chat.tool.ToolExecutor;
import com.xxx.ragdoc.application.chat.tool.ToolResult;
import com.xxx.ragdoc.domain.shared.PipelineType;
import com.xxx.ragdoc.domain.shared.StateHint;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 明确搜索意图的受控工具路径。当前只允许只读 keyword_search:v1。 */
@Component
@RequiredArgsConstructor
public class ToolExecutionPipeline implements ChatPipeline {
    private final ToolExecutor toolExecutor;

    @Override
    public PipelineType type() {
        return PipelineType.TOOL_EXECUTION;
    }

    @Override
    public ChatResult execute(ChatCommand command, ChatExecutionContext context) {
        ToolResult<SearchOutput> result = executeTool(command, context);
        if (result.status() == com.xxx.ragdoc.application.chat.tool.ToolStatus.EMPTY_RESULT) {
            return ChatResult.of(StateHint.NO_RECALL, "未通过工具找到相关文档。", context.traceId());
        }
        if (result.status() != com.xxx.ragdoc.application.chat.tool.ToolStatus.SUCCESS
                || result.output() == null) {
            return ChatResult.of(StateHint.LLM_DEGRADED, "检索工具暂不可用。", context.traceId());
        }
        List<ChatResult.Citation> citations =
                result.output().evidences().stream().map(ToolExecutionPipeline::citation).toList();
        String answer = "已通过关键词检索找到 " + citations.size() + " 条相关资料，请查看引用。";
        return ChatResult.of(answer, citations, StateHint.OK, context.traceId());
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command, ChatExecutionContext context) {
        return Flux.defer(
                () -> {
                    ChatResult result = execute(command, context);
                    Flux<ChatStreamEvent> citations =
                            result.citations().isEmpty()
                                    ? Flux.empty()
                                    : Flux.just(
                                            new ChatStreamEvent.CitationsEvent(result.citations()));
                    return citations.concatWith(
                            Flux.just(
                                    new ChatStreamEvent.DeltaEvent(result.answer()),
                                    new ChatStreamEvent.DoneEvent(
                                            context.traceId().value(), result.stateHint().name())));
                });
    }

    private ToolResult<SearchOutput> executeTool(
            ChatCommand command, ChatExecutionContext context) {
        SearchInput input =
                new SearchInput(
                        command.query(),
                        command.topK(),
                        new SearchInput.SearchFilters(
                                command.source(), command.version(), command.language()));
        return toolExecutor.execute(
                KeywordSearchTool.NAME,
                KeywordSearchTool.VERSION,
                input,
                new ToolExecutor.ToolCallRequest(
                        context.requestId(),
                        context.requestId(),
                        contextDeadline(context),
                        "active"));
    }

    private static java.time.Instant contextDeadline(ChatExecutionContext context) {
        long timeout = context.executionPolicy().chatTimeoutMillis();
        return java.time.Instant.now().plusMillis(timeout > 0 ? timeout : 30000);
    }

    private static ChatResult.Citation citation(Evidence e) {
        Object page = e.metadata().get("page");
        int pageNo = page instanceof Number n ? n.intValue() : 0;
        Object section = e.metadata().get("sectionPath");
        @SuppressWarnings("unchecked")
        List<String> sectionPath = section instanceof List<?> l ? (List<String>) l : List.of();
        String snippet = e.content().substring(0, Math.min(200, e.content().length()));
        return new ChatResult.Citation(
                e.chunkId(), e.documentId(), pageNo, snippet, e.content(), sectionPath);
    }
}
