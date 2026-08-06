package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * PR-5: HarnessProvider ↔ 调用方的双向类型桥接。
 *
 * <p>不同组件 (Router / Tool / 未来 Planner) 用各自 record, ObjectMapper 通用但需要 helper 把
 * canonical JsonNode 转回原 typed 对象; Helper 还构造 outcome/error。
 *
 * <p>每个调用方提供一个 adapter 实现 (e.g. ToolHarnessAdapter, RouterHarnessAdapter)。
 */
public interface ObjectResultMapper {

    /**
     * @return 把 canonical request Node 与 caller request 对象的 hash (caller 先 sanitize 再 sha256)
     */
    String requestHash(Object request);

    /**
     * 从 canonical response Node + outcome 还原 typed result。
     *
     * <p>FAIL/TIMEOUT/PERMISSION 等异常类 outcome → 抛异常 (而不是返回 null result); Provider packaging
     *
     * @throws RuntimeException 当 FixtureError 类别是 TIMEOUT/PERMISSION/INVALID_ARG/GENERIC 时, 让调用方原样捕获
     */
    Object fromFixtureResponse(JsonNode responseNode, FixtureError error);

    /**
     * 从 live result + 抛出的 exception 类型构造 {@link FixtureOutcome.OutcomeResult} 让 Provider 写入。
     *
     * @param liveResult 非 null 表示成功; null + thrown 表示该 outcome 是 error
     * @param thrown liveCall 抛出的异常 (没异常时 null)
     */
    FixtureOutcome.OutcomeResult toOutcome(Object liveResult, Throwable thrown);
}
