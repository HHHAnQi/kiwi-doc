package com.xxx.ragdoc.application.chat.harness;

import java.util.function.Supplier;

/**
 * PR-5 / EMS-PR5: Harness Invocation 入口。所有可被记录的组件调用经过这一层, <b>不</b> 在各组件内部分支判断 mode。
 *
 * <p>调用约定:
 *
 * <ul>
 *   <li>LIVE  — 直接执行 {@code liveCall.get()}, 不读不写 Fixture
 *   <li>RECORD — 执行 {@code liveCall.get()}, 把请求/响应序列化脱敏后写到 FixtureStore; 返回原结果
 *   <li>REPLAY — <b>不</b> 调 {@code liveCall}; 直接读 FixtureStore; 缺失/不匹配/损坏 → FixtureUnavailableException
 * </ul>
 *
 * <p>类型支持: {@link ObjectResultMapper} 让调用方按 resultClass 反序列化 — 对 ToolResult / RouterDecision /
 * VerificationResult 等 record 透明。RuntimeException 子类按 {@link InvocationResult#error()} 重建。
 */
public interface HarnessProvider {

    HarnessMode mode();

    <RES> InvocationResult<RES> invoke(
            ComponentInvocation invocation,
            Object request,
            Supplier<RES> liveCall,
            Class<RES> responseType,
            ObjectResultMapper mapper);
}
