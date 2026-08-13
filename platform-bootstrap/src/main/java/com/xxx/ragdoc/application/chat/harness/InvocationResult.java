package com.xxx.ragdoc.application.chat.harness;

/**
 * PR-5: Harness invoke 的统一结果包装。REPLAY 用 result/error; RECORD/LIVE 把 liveCall 的结果透传给 caller。
 *
 * @param result 反序列化后的 typed result; SUCCESS/EMPTY 时存在
 * @param error 结构化 error (outcome.error 的镜像), 仅 ERROR 类
 * @param outcome 记录用 outcome; REPLAY 中调用方据 outcome 决定抛/返回
 */
public record InvocationResult<RES>(
        RES result, FixtureError error, FixtureOutcome.OutcomeResult outcome) {

    public static <RES> InvocationResult<RES> ok(RES r, FixtureOutcome.OutcomeResult outcome) {
        return new InvocationResult<>(r, null, outcome);
    }
}
