package com.xxx.ragdoc.application.chat.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.harness.ComponentInvocation;
import com.xxx.ragdoc.application.chat.harness.HarnessComponentType;
import com.xxx.ragdoc.application.chat.harness.HarnessMode;
import com.xxx.ragdoc.application.chat.harness.HarnessProperties;
import com.xxx.ragdoc.application.chat.harness.HarnessProvider;
import com.xxx.ragdoc.application.chat.harness.InvocationContext;
import com.xxx.ragdoc.application.chat.harness.InvocationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-7a / EMS-PR7 §4.5 + §10: Planner LIVE/RECORD/REPLAY 包装。
 *
 * <p>委托真实 Planner (RuleTemplate / Model — 由 PlannedAgentPipeline 依 {@link
 * PlannerProperties#isModelEnabled()} 注入); 本 Provider 不感知委托。
 *
 * <p>{@code invoke} 走通用 {@link HarnessProvider} — key/canonical/serialization 已由项目既有 {@code
 * CanonicalJson.replayKeyFor(...)} 统一处理。Fixture 缺失 → {@code FixtureUnavailableException} 由
 * HarnessProvider 统一抛; 这里转 {@link PlannerException.Reason#FIXTURE_UNAVAILABLE}。
 *
 * <p>PR-7a 第一版 callIndex = {@code request.replanIndex()} (=0 initial, =1 唯一允许的 replan)。
 */
@Slf4j
@Component
public class HarnessAwarePlannerProvider implements PlannerProvider {

    private static final String PLANNER_VERSION_TAG = "planner-v1";
    private static final String DEFAULT_INDEX_VERSION = "default";
    private static final String MASKED_TENANT = "";

    private final PlannerProvider delegate;
    private final HarnessProvider harnessProvider;
    private final HarnessProperties harnessProperties;

    public HarnessAwarePlannerProvider(
            PlannerProvider delegate,
            HarnessProvider harnessProvider,
            HarnessProperties harnessProperties,
            ObjectMapper objectMapper /* 显式注入避免误用; 当前 PR 不直接序列化 (HarnessProvider 内部完成) */) {
        this.delegate = delegate;
        this.harnessProvider = harnessProvider;
        this.harnessProperties = harnessProperties;
        // objectMapper 留作 record/replay 扩展点 (PR-7c replan key 收紧时使用)
        if (objectMapper == null) throw new IllegalArgumentException("objectMapper");
    }

    @Override
    public PlannerResponse plan(PlannerRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        if (!harnessProperties.isEnabled()) {
            return delegate.plan(request);
        }
        HarnessMode mode = harnessProperties.getMode();
        if (mode == HarnessMode.LIVE) {
            return delegate.plan(request);
        }

        String runId = request.runId();
        int callIndex = request.replanIndex();
        ComponentInvocation invocation =
                new ComponentInvocation(
                        runId,
                        runId,
                        HarnessComponentType.PLANNER,
                        "planner",
                        PLANNER_VERSION_TAG,
                        callIndex,
                        new InvocationContext(
                                runId,
                                MASKED_TENANT,
                                "" /* scopeVersion */,
                                DEFAULT_INDEX_VERSION,
                                runId,
                                ""));

        try {
            InvocationResult<PlannerResponse> result =
                    harnessProvider.invoke(
                            invocation,
                            request,
                            () -> delegate.plan(request),
                            PlannerResponse.class,
                            null /* mapper: 让 HarnessProvider 用默认 record mapper */);
            if (result.error() != null) {
                // HarnessProvider 标记失败 (Fixture 缺失等)
                throw new PlannerException(
                        PlannerException.Reason.FIXTURE_UNAVAILABLE,
                        "planner harness failure: "
                                + result.error()
                                + " run="
                                + runId
                                + " mode="
                                + mode);
            }
            PlannerResponse body = result.result();
            if (body == null) {
                throw new PlannerException(
                        PlannerException.Reason.SCHEMA_VIOLATION,
                        "planner harness returned null run=" + runId + " mode=" + mode);
            }
            return body;
        } catch (
                com.xxx.ragdoc.application.chat.harness.FixtureStore.FixtureUnavailableException
                        fue) {
            log.warn(
                    "planner.harness.fixture_unavailable run={} mode={} reason={}",
                    runId,
                    mode,
                    fue.reason);
            throw new PlannerException(
                    PlannerException.Reason.FIXTURE_UNAVAILABLE,
                    "planner fixture 不可用 run=" + runId + ": " + fue.getMessage(),
                    fue);
        } catch (
                com.xxx.ragdoc.application.chat.harness.FixtureStore.FixtureConflictException fce) {
            log.warn(
                    "planner.harness.fixture_conflict run={} mode={} err={}",
                    runId,
                    mode,
                    fce.getMessage());
            throw new PlannerException(
                    PlannerException.Reason.FIXTURE_CONFLICT,
                    "planner record 冲突 run=" + runId + ": " + fce.getMessage(),
                    fce);
        } catch (PlannerException pe) {
            throw pe;
        } catch (RuntimeException re) {
            throw new PlannerException(
                    PlannerException.Reason.PROVIDER_ERROR,
                    "planner harness 异常 run=" + runId + ": " + re.getMessage(),
                    re);
        }
    }
}
