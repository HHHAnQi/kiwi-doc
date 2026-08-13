package com.xxx.ragdoc.application.chat.pipeline;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.command.ChatResult;
import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.domain.shared.PipelineType;
import reactor.core.publisher.Flux;

/**
 * PR-2 / EMS-PR2: 可插拔 chat pipeline 契约。
 *
 * <p>实现方 (PR-2 只 {@code ClassicRagPipeline}) 通过 {@link #type()} 自我标识, 注册到 {@code
 * ChatPipelineRegistry}; Orchestrator 按 type 查找。
 *
 * <h2>同步与流式拆两个显式方法的原因</h2>
 *
 * <ul>
 *   <li>同步路径要把整段答案 + Evidence + 状态一起返回 ({@link ChatResult}), 必须等 LLM 结束
 *   <li>流式路径必须以 Reactor {@code Flux} 增量产出 token, 并严格保证单终态 (PR-0 不变量)
 *   <li>当前 {@code ChatService} 本就用两套代码维护 (chat / chatStream 不同的 LLM 调用与 trace 节奏), 抽 2
 *       个方法是对现状的等价表达, 而非凭空制造复杂度
 * </ul>
 *
 * <p>不变量 (跨 PR):
 *
 * <ul>
 *   <li>实现不得依赖全局可变状态; 所有运行时输入只能从 {@code command} + {@code context} 读
 *   <li>tenantId 永远从 {@code context.principal().tenantId()} 派生, 不接受 command 传值
 *   <li>SSE 流必须只产一个终态 (DoneEvent); timeout / cancel / 异常 不能再写 OK Trace
 *   <li>同步路径必须保留既有 {@link ChatResult} schema (含 PR-1 {@code evidenceSnapshot} 字段)
 * </ul>
 */
public interface ChatPipeline {

    /** 自我标识, 注册键。 */
    PipelineType type();

    /**
     * 同步执行: 等到有完整 {@link ChatResult} 后返回。Orchestrator 把这一结果照原样回 Controller。
     *
     * @param command 用户业务输入 (无 tenantId / userId / ACL)
     * @param context 服务端构造的不可变执行上下文 (含 Principal / TraceId / effectivePipeline)
     */
    ChatResult execute(ChatCommand command, ChatExecutionContext context);

    /** 流式执行: 返回 Reactor Flux, 由 Controller 转 SSE。必须保证 PR-0 单终态不变量。 */
    Flux<ChatStreamEvent> stream(ChatCommand command, ChatExecutionContext context);
}
