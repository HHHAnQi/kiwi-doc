package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * PR-5: REPLAY 模式。<b>不</b> 调 liveCall; 直接从 {@link FixtureStore} 读取, 按 strict-replay 规则校验。
 *
 * <p>严格匹配 (EMS-PR5 §13.1):
 *
 * <ol>
 *   <li>Fixture 缺失 → FIXTURE_NOT_FOUND
 *   <li>request hash 不一致 → FIXTURE_REQUEST_MISMATCH
 *   <li>component name/version 不一致 → FIXTURE_COMPONENT_VERSION_MISMATCH
 *   <li>fixture schema 不兼容 → FIXTURE_SCHEMA_MISMATCH
 *   <li>JSON 损坏 → FIXTURE_CORRUPTED
 * </ol>
 *
 * <p>禁止回退 LIVE / 自动相似 Fixture / 返回空掩盖缺失。
 */
@Slf4j
public class ReplayHarnessProvider implements HarnessProvider {

    private final FixtureStore store;
    private final CanonicalJson canonical;
    private final ObjectMapper mapper;
    private final boolean strictReplay;

    public ReplayHarnessProvider(FixtureStore store, ObjectMapper mapper, boolean strictReplay) {
        this.store = store;
        this.mapper = mapper;
        this.canonical = new CanonicalJson(mapper);
        this.strictReplay = strictReplay;
    }

    @Override
    public HarnessMode mode() {
        return HarnessMode.REPLAY;
    }

    @Override
    public <RES> InvocationResult<RES> invoke(
            ComponentInvocation invocation,
            Object request,
            java.util.function.Supplier<RES> liveCall,
            Class<RES> responseType,
            ObjectResultMapper resultMapper) {
        String replayKey =
                canonical.replayKeyFor(
                        invocation.caseId(),
                        invocation.componentType(),
                        invocation.componentName(),
                        invocation.componentVersion(),
                        invocation.callIndex(),
                        request,
                        invocation.context().permissionScopeVersion(),
                        invocation.context().indexVersion());

        FixtureRecord record;
        try {
            record = store.find(replayKey)
                    .orElseThrow(() -> fail(invocation, replayKey,
                            FixtureStore.FixtureUnavailableException.Reason.NOT_FOUND,
                            "fixture not found"));
        } catch (FixtureStore.FixtureUnavailableException ex) {
            throw fail(invocation, replayKey, ex.reason, ex.getMessage());
        }

        // 1. componentVersion 严格匹配 (实际 replayKey 计算已含 version, 这里多一道校验防 store 实现误命中)
        if (!record.componentName().equals(invocation.componentName())
                || !record.componentVersion().equals(invocation.componentVersion())
                || record.componentType() != invocation.componentType()
                || record.callIndex() != invocation.callIndex()) {
            throw fail(invocation, replayKey,
                    FixtureStore.FixtureUnavailableException.Reason.COMPONENT_VERSION_MISMATCH,
                    "component identity mismatch in stored fixture");
        }

        // 2. request hash 严格匹配 (注意: store 内的 requestHash 是 record 时 caller 算的; 现在用同样算法复算)
        String currentRequestHash = resultMapper.requestHash(request);
        if (!currentRequestHash.equals(record.requestHash())) {
            throw fail(invocation, replayKey,
                    FixtureStore.FixtureUnavailableException.Reason.REQUEST_MISMATCH,
                    "request hash mismatch current=" + currentRequestHash + " stored=" + record.requestHash());
        }

        // 3. permissionScope / index 版本严格匹配 (replayKey 已包含, 这里 double-check)
        if (!invokeContextMatches(invocation, record)) {
            throw fail(invocation, replayKey,
                    FixtureStore.FixtureUnavailableException.Reason.REQUEST_MISMATCH,
                    "permissionScopeVersion/indexVersion mismatch");
        }

        // 4. 从 fixture 还原 typed 响应/异常
        JsonNode responseNode = record.normalizedResponse();
        Object typed = resultMapper.fromFixtureResponse(responseNode, record.error());
        // typed 可能是 result (SUCCESS / EMPTY), 或者 fromFixtureResponse 已抛出对应 RuntimeException
        @SuppressWarnings("unchecked")
        RES r = (RES) typed;
        return InvocationResult.ok(r, record.outcome());
    }

    private boolean invokeContextMatches(ComponentInvocation invocation, FixtureRecord record) {
        return java.util.Objects.equals(
                        invocation.context().permissionScopeVersion(),
                        record.metadata().permissionScopeVersion())
                && java.util.Objects.equals(
                        invocation.context().indexVersion(), record.metadata().indexVersion());
    }

    private static RuntimeException fail(
            ComponentInvocation invocation,
            String replayKey,
            FixtureStore.FixtureUnavailableException.Reason reason,
            String message) {
        String code =
                switch (reason) {
                    case NOT_FOUND -> "FIXTURE_NOT_FOUND";
                    case REQUEST_MISMATCH -> "FIXTURE_REQUEST_MISMATCH";
                    case COMPONENT_VERSION_MISMATCH -> "FIXTURE_COMPONENT_VERSION_MISMATCH";
                    case SCHEMA_MISMATCH -> "FIXTURE_SCHEMA_MISMATCH";
                    case CORRUPTED -> "FIXTURE_CORRUPTED";
                };
        log.warn(
                "harness.replay_failed component={} callidx={} reason={} msg={}",
                invocation.componentName(), invocation.callIndex(), code, message);
        return new FixtureStore.FixtureUnavailableException(replayKey, reason, code + ": " + message);
    }
}
