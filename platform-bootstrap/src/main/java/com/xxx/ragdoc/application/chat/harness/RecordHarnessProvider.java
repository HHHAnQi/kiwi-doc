package com.xxx.ragdoc.application.chat.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * PR-5: RECORD 模式。执行 liveCall, 把请求/响应脱敏并原子写到 {@link FixtureStore}。
 *
 * <p>关键行为:
 *
 * <ul>
 *   <li>执行 liveCall; 异常时也记录 (用 ERROR outcome)
 *   <li>用 {@link CanonicalJson} 做 sanitize + canonical + sha256
 *   <li>请求 hash + 响应 Node 都进 FixtureRecord
 *   <li>相同 key 内容一致 → 幂等 (FileFixtureStore 内部判断)
 *   <li>不同 key 内容 → FixtureConflictException (RECORD 立即抛, 让 caller 知道重跑有歧义)
 *   <li>返回值<b>就是</b> liveCall 的原结果 (不上 REPLAY 查回来)
 * </ul>
 */
@Slf4j
public class RecordHarnessProvider implements HarnessProvider {

    private final FixtureStore store;
    private final CanonicalJson canonical;
    private final ObjectMapper mapper;
    private final String sourceMode; // 写入 fixture metadata 标识来源 (e.g. "test" / "ci")

    public RecordHarnessProvider(
            FixtureStore store, ObjectMapper mapper, String sourceMode) {
        this.store = store;
        this.mapper = mapper;
        this.canonical = new CanonicalJson(mapper);
        this.sourceMode = sourceMode == null ? "record" : sourceMode;
    }

    @Override
    public HarnessMode mode() {
        return HarnessMode.RECORD;
    }

    @Override
    public <RES> InvocationResult<RES> invoke(
            ComponentInvocation invocation,
            Object request,
            java.util.function.Supplier<RES> liveCall,
            Class<RES> responseType,
            ObjectResultMapper resultMapper) {
        // 1. pre-compute request hash + canonical node (失败马上抛, 不调 liveCall)
        String requestHash = resultMapper.requestHash(request);
        JsonNode canonicalRequest =
                canonical.canonicalize(canonical.toJsonNode(request));

        // 2. 执行 + 捕获异常类型
        RES liveResult = null;
        Throwable thrown = null;
        try {
            liveResult = liveCall.get();
        } catch (RuntimeException | Error e) {
            thrown = e;
        }

        // 3. 计算结果 outcome
        FixtureOutcome.OutcomeResult outcome = resultMapper.toOutcome(liveResult, thrown);
        JsonNode responseNode = outcome.structuredResponse(); // mapper 已负责脱敏; canonical 再走一遭
        JsonNode canonicalResponse = responseNode == null ? null : canonical.canonicalize(responseNode);

        // 4. 构造 fixture record + 原子写
        String replayKey =
                canonical.replayKeyFor(
                        invocation.caseId(),
                        invocation.componentType(),
                        invocation.componentName(),
                        invocation.componentVersion(),
                        invocation.callIndex(),
                        request,
                        invocation.context().tenantId(),
                        invocation.context().permissionScopeVersion(),
                        invocation.context().indexVersion());
        FixtureMetadata meta =
                new FixtureMetadata(
                        "", // PR-5: 不依赖系统时间判定 Fixture validity (EMS §8.3), 用结构性字段
                        "v1",
                        "v1",
                        "SHA-256",
                        invocation.context().permissionScopeVersion(),
                        invocation.context().indexVersion(),
                        "v1",
                        sourceMode,
                        null);
        FixtureRecord record =
                new FixtureRecord(
                        "v1",
                        replayKey,
                        invocation.componentType(),
                        invocation.componentName(),
                        invocation.componentVersion(),
                        invocation.callIndex(),
                        requestHash,
                        canonicalRequest,
                        outcome,
                        canonicalResponse,
                        outcome.error(),
                        meta);
        try {
            store.save(record);
        } catch (FixtureStore.FixtureConflictException conflict) {
            // 内容冲突让 caller 决定 (默认上抛; 测试可 catch)
            throw conflict;
        } catch (RuntimeException e) {
            // 写文件失败不阻塞 — 仍然返回 liveCall 结果; 但 log+metrics (这里简化为 log)
            log.warn("harness.record_save_failed key={} err={}",
                    replayKey == null ? "" : replayKey.substring(0, 12), e.toString());
        }

        // 5. 异常需重新抛 (让 caller 看到真实业务失败)
        if (thrown instanceof RuntimeException re) throw re;
        if (thrown instanceof Error err) throw err;

        return InvocationResult.ok(liveResult, outcome);
    }
}
