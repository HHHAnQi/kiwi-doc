package com.xxx.ragdoc.application.chat.harness;

/**
 * PR-5: LIVE 模式。直接执行 liveCall, 不读不写 Fixture; 仅 metrics (调用方负责)。
 *
 * <p><b>不修改</b> request / response; <b>不修改</b> 异常类型。
 */
public class LiveHarnessProvider implements HarnessProvider {

    @Override
    public HarnessMode mode() {
        return HarnessMode.LIVE;
    }

    @Override
    public <RES> InvocationResult<RES> invoke(
            ComponentInvocation invocation,
            Object request,
            java.util.function.Supplier<RES> liveCall,
            Class<RES> responseType,
            ObjectResultMapper mapper) {
        RES r = liveCall.get(); // 异常原样冒泡, 不包装
        return InvocationResult.ok(r, mapper.toOutcome(r, null));
    }
}
