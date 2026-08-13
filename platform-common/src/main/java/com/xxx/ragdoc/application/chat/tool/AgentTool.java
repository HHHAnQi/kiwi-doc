package com.xxx.ragdoc.application.chat.tool;

/**
 * PR-4 / EMS-PR4: Agent Tool 契约接口。把现有 RetrieveService / ChunkQueryService / CitationVerifierPort
 * 等能力标准化为可测试、可授权、可观测的 Tool, 给后续 PR-5 (Harness/Replay)、PR-6 (Executor/AgentState)、 PR-7 (Planner)
 * 复用。
 *
 * <h2>接口语义</h2>
 *
 * <ul>
 *   <li>{@link #descriptor()} 返回 Tool 自我标识; Registry 按 name+version 索引
 *   <li>{@link #inputType()} 返回 input 的 Class, 让 Executor / Validator 用反射做 Bean Validation + detect
 *       客户端偷传 tenantId/userId 等 banned 字段
 *   <li>{@link #execute(I, ToolExecutionContext)} 是 Tool 主体逻辑; 实现 <b>不应</b> 自填 latencyMs / callId /
 *       trace (那些由 ToolExecutor 包装), 只负责业务逻辑 + 直接 ToolError 语义
 * </ul>
 *
 * <h2>实现守则</h2>
 *
 * <ul>
 *   <li>输入 output 类型必须各自一个 typed record; 禁止 Map&lt;String,Object&gt; 作 input/output
 *   <li>tenantId / userId / roles <b>不能</b> 出现在 input — 必须从 {@link
 *       ToolExecutionContext#principal()} 读
 *   <li>Tool 不直接抛 RuntimeException 给 caller (除非是代码 bug); <b>正常 failure 路径产出 ToolResult.failure</b>
 *   <li>Tool 不负责 latency 度量 / dedup / cache; 由 ToolExecutor 接管
 *   <li>所有 read-only Tool 标 {@link ToolDescriptor#idempotent()}=true (所有 5 个第一版 Tool 都是 read)
 * </ul>
 *
 * @param <I> tool 特定 input 类型
 * @param <O> tool 特定 output 类型
 */
public interface AgentTool<I extends ToolInput, O extends ToolOutput> {

    /** Tool 自我标识 (name+version+权限+timeout+schema 等)。Executor / Registry 不解析 description 文字。 */
    ToolDescriptor descriptor();

    /** Tool 输入类型; 让 Executor 用反射做 Bean Validation + banned-field 检测。 */
    Class<I> inputType();

    /** Tool 输出类型; 主要用于 trace / replay fixture schema 比对。 */
    Class<O> outputType();

    /**
     * 执行 Tool 主体。Executor 在调用前已经做: input 校验 → deadline check → dedup check → ACL pre-check。
     * Executor 在调用后做: ACL evidence post-check → metrics → trace → cache fill。
     *
     * <p>实现要求:
     *
     * <ul>
     *   <li>不依赖长 DB 事务 timeout; 自检 {@link ToolExecutionContext#isExpired()} 后用 ToolStatus.TIMEOUT 退
     *   <li>下游 (Milvus / Embedding / LLM) 真失败 → ToolStatus.DEPENDENCY_UNAVAILABLE; 不要伪装为
     *       EMPTY_RESULT
     *   <li>客户输入非法 → ToolStatus.INVALID_ARGUMENT; 不要伪装 TIMEOUT
     *   <li>权限范围内无可用文档 → ToolStatus.EMPTY_RESULT (与 PERMISSION_DENIED 区分: 后者是 ACL 显式拒)
     * </ul>
     */
    ToolResult<O> execute(I input, ToolExecutionContext context);

    /** 工具默认的 name+version 字符串 (Registry / Trace 用)。不能用于 Registry key 校验 (用 descriptor())。 */
    default String fullName() {
        return descriptor().name() + ":" + descriptor().version();
    }
}
