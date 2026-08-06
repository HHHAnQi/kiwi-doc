package com.xxx.ragdoc.application.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR-5 / EMS-PR5 §20: LIVE + RECORD + ReplayProvider 端到端, 用 tmpdir 隔离 + 真实 ToolResult ↔ Fixture。
 *
 * <p>覆盖: 写入原子性, idempotent skip, conflict 抛, replay miss/mismatch 不回退 liveCall。
 */
@DisplayName("HarnessProvider LIVE/RECORD/REPLAY 端到端")
class HarnessProviderEndToEndTest {

    @TempDir Path tmp;
    private ObjectMapper mapper;
    private CanonicalJson canonical;
    private FileFixtureStore store;
    private ObjectResultMapper passthroughMapper;

    @BeforeEach
    void setup() {
        mapper = new ObjectMapper();
        canonical = new CanonicalJson(mapper);
        store = new FileFixtureStore(tmp.toString(), mapper);
        // 简单 mapper: request = 字符串, response = 字符串
        passthroughMapper =
                new ObjectResultMapper() {
                    @Override
                    public String requestHash(Object request) {
                        return canonical.hashObject(Map.of("req", request));
                    }

                    @Override
                    public Object fromFixtureResponse(JsonNode responseNode, FixtureError error) {
                        if (error != null) {
                            throw new RuntimeException(error.errorCode() + ": " + error.safeMessage());
                        }
                        return responseNode == null
                                ? null
                                : responseNode.asText();
                    }

                    @Override
                    public FixtureOutcome.OutcomeResult toOutcome(Object liveResult, Throwable thrown) {
                        if (thrown != null) {
                            FixtureError err = FixtureError.of(
                                    "X_ERR", thrown.getMessage(), FixtureError.Category.GENERIC);
                            return FixtureOutcome.OutcomeResult.error(err);
                        }
                        return FixtureOutcome.OutcomeResult.success(
                                JsonNodeFactory.instance.textNode(String.valueOf(liveResult)));
                    }
                };
    }

    @AfterEach
    void tearDown() {
        // @TempDir 自动 clear
    }

    private ComponentInvocation invocation(String caseId, int callIdx) {
        return new ComponentInvocation(
                caseId,
                "run-" + caseId,
                HarnessComponentType.TOOL,
                "fake_tool",
                "v1",
                callIdx,
                new InvocationContext("req-1", "tenant-A", "ps-1", "iv-1", "trace-1", "uhash"));
    }

    // ── LIVE ───────────────────────────────────

    @Test
    @DisplayName("LIVE: 调真实 supplier, 不读不写 fixture, 原结果透传")
    void liveCallsRealSupplier() throws Exception {
        LiveHarnessProvider live = new LiveHarnessProvider();
        String result = live.invoke(
                invocation("c1", 0), "request", () -> "result-x", String.class, passthroughMapper).result();
        assertThat(result).isEqualTo("result-x");
        // 没写任何 fixture 文件
        assertThat(Files.exists(tmp)).isTrue();
        assertThat(Files.walk(tmp).count()).isOne(); // 只有 tmp 本身
    }

    // ── RECORD ─────────────────────────────────

    @Test
    @DisplayName("RECORD: 写入 fixture; 同 key 同内容幂等 skip")
    void recordWritesAndIdempotent() throws Exception {
        RecordHarnessProvider record = new RecordHarnessProvider(store, mapper, "test");

        record.invoke(invocation("c1", 0), "q", () -> "ans", String.class, passthroughMapper);
        long afterFirst = Files.walk(tmp).filter(Files::isRegularFile).count();
        assertThat(afterFirst).isOne(); // 一个 fixture 文件

        // 第二次同 key (caseId 相同 + req 相同 + scope 相同) → idempotent skip
        record.invoke(invocation("c1", 0), "q", () -> "ans", String.class, passthroughMapper);
        long afterSecond = Files.walk(tmp).filter(Files::isRegularFile).count();
        assertThat(afterSecond).isEqualTo(afterFirst); // 文件数不变=幂等
    }

    @Test
    @DisplayName("RECORD: 同 key 不同内容 → FixtureConflictException")
    void recordConflict() {
        RecordHarnessProvider record = new RecordHarnessProvider(store, mapper, "test");
        record.invoke(invocation("c1", 0), "q1", () -> "ans1", String.class, passthroughMapper);

        // 同 caseId+callIndex/scopes 但不同 request → 同 replayKey 但 fixture 内容不同 → conflict
        // 注意: CanonicalJson.banned 字段集中作 hash, 但 "q1" vs "q2" 实际产生不同 hash → 不同 replayKey
        // 我们让两个 request 字符串相同但 liveCall 返回不同, 这样 ReplayKey 一样但 normalizedResponse 不同
        assertThatThrownBy(
                        () -> record.invoke(invocation("c1", 0), "q1", () -> "different-ans", String.class, passthroughMapper))
                .isInstanceOf(FixtureStore.FixtureConflictException.class);
    }

    // ── REPLAY ─────────────────────────────────

    @Test
    @DisplayName("REPLAY: 成功命中, 不调 liveCall")
    void replayHitsWithoutLiveCall() {
        // 先 RECORD 一份
        RecordHarnessProvider record = new RecordHarnessProvider(store, mapper, "test");
        record.invoke(invocation("c1", 0), "q", () -> "ans", String.class, passthroughMapper);

        // REPLAY 用空 supplier (不应该被调)
        ReplayHarnessProvider replay = new ReplayHarnessProvider(store, mapper, true);
        String result = replay.invoke(
                invocation("c1", 0), "q",
                () -> { throw new AssertionError("REPLAY 不应调 liveCall"); },
                String.class, passthroughMapper).result();
        assertThat(result).isEqualTo("ans");
    }

    @Test
    @DisplayName("REPLAY: fixture 缺失 → NOT_FOUND, 不回退 liveCall")
    void replayMissingFails() {
        ReplayHarnessProvider replay = new ReplayHarnessProvider(store, mapper, true);
        assertThatThrownBy(
                        () -> replay.invoke(
                                invocation("never-recorded", 0), "q",
                                () -> { throw new AssertionError("missing 不应回退 liveCall"); },
                                String.class, passthroughMapper))
                .isInstanceOf(FixtureStore.FixtureUnavailableException.class)
                .satisfies(ex -> {
                    FixtureStore.FixtureUnavailableException fue = (FixtureStore.FixtureUnavailableException) ex;
                    assertThat(fue.reason)
                            .isEqualTo(FixtureStore.FixtureUnavailableException.Reason.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("REPLAY: 不同 request → 不同 replayKey → NOT_FOUND (canonical request 已在 key 中)")
    void replayRequestChangeIsDifferentKey() {
        RecordHarnessProvider record = new RecordHarnessProvider(store, mapper, "test");
        record.invoke(invocation("c1", 0), "q1", () -> "ans1", String.class, passthroughMapper);

        ReplayHarnessProvider replay = new ReplayHarnessProvider(store, mapper, true);
        // q2 与 q1 不同 → canonical 不同 → replayKey 不同 → NOT_FOUND (而非 REQUEST_MISMATCH)
        assertThatThrownBy(
                        () -> replay.invoke(
                                invocation("c1", 0), "q2",
                                () -> { throw new AssertionError(); },
                                String.class, passthroughMapper))
                .isInstanceOf(FixtureStore.FixtureUnavailableException.class)
                .satisfies(ex -> assertThat(((FixtureStore.FixtureUnavailableException) ex).reason)
                        .isEqualTo(FixtureStore.FixtureUnavailableException.Reason.NOT_FOUND));
    }

    @Test
    @DisplayName("REPLAY: 不同 permissionScopeVersion 不误命中")
    void replayDifferentScope() {
        RecordHarnessProvider record = new RecordHarnessProvider(store, mapper, "test");
        // ps-1 record
        ComponentInvocation inv1 = new ComponentInvocation(
                "c1", "run1", HarnessComponentType.TOOL, "fake_tool", "v1", 0,
                new InvocationContext("req", "tenant-A", "ps-1", "iv-1", "t", ""));
        record.invoke(inv1, "q", () -> "ans", String.class, passthroughMapper);

        // REPLAY with ps-2 → replayKey 不同 (canonical 内 scope 变) → NOT_FOUND
        ComponentInvocation inv2 = new ComponentInvocation(
                "c1", "run1", HarnessComponentType.TOOL, "fake_tool", "v1", 0,
                new InvocationContext("req", "tenant-A", "ps-2", "iv-1", "t", ""));
        ReplayHarnessProvider replay = new ReplayHarnessProvider(store, mapper, true);
        assertThatThrownBy(() -> replay.invoke(inv2, "q",
                        () -> { throw new AssertionError(); }, String.class, passthroughMapper))
                .isInstanceOf(FixtureStore.FixtureUnavailableException.class);
    }

    @Test
    @DisplayName("REPLAY: corrupted fixture → CORRUPTED (覆盖 FileFixtureStore 容错)")
    void replayCorruptedFailsClosed() throws Exception {
        // 先 record 一份, 然后手动破坏文件内容
        RecordHarnessProvider record = new RecordHarnessProvider(store, mapper, "test");
        record.invoke(invocation("c1", 0), "q", () -> "ans", String.class, passthroughMapper);

        // 找到 fixture 文件 (root/xx/yy.json) 并写"INVALID"
        Optional<Path> any = Files.walk(tmp).filter(Files::isRegularFile).findFirst();
        assertThat(any).isPresent();
        Files.writeString(any.get(), "INVALID-CORRUPTED");

        ReplayHarnessProvider replay = new ReplayHarnessProvider(store, mapper, true);
        assertThatThrownBy(() -> replay.invoke(invocation("c1", 0), "q",
                        () -> { throw new AssertionError(); }, String.class, passthroughMapper))
                .isInstanceOf(FixtureStore.FixtureUnavailableException.class)
                .satisfies(ex -> assertThat(((FixtureStore.FixtureUnavailableException) ex).reason)
                        .isEqualTo(FixtureStore.FixtureUnavailableException.Reason.CORRUPTED));
    }

    @Test
    @DisplayName("REPLAY: exception outcome 被恢复 (不假装 success)")
    void replayRestoresErrorOutcome() {
        RecordHarnessProvider record = new RecordHarnessProvider(store, mapper, "test");
        // liveCall 抛 IllegalStateException → RecordHarnessProvider 抓但要 rethrow (业务异常不吞)
        // 但在 rethrow 前已写 fixture. 我们用 try-catch 容忍 record 阶段的业务 rethrow; 然后用同样参数 replay
        assertThatThrownBy(() -> record.invoke(invocation("c1", 0), "q", () -> {
            throw new IllegalStateException("boom");
        }, String.class, passthroughMapper)).isInstanceOf(IllegalStateException.class);

        // REPLAY: fixture 已写 error outcome → fromFixtureResponse 看 error 抛 RuntimeException
        ReplayHarnessProvider replay = new ReplayHarnessProvider(store, mapper, true);
        assertThatThrownBy(() -> replay.invoke(invocation("c1", 0), "q",
                        () -> { throw new AssertionError(); }, String.class, passthroughMapper))
                .isInstanceOf(RuntimeException.class);
    }
}
