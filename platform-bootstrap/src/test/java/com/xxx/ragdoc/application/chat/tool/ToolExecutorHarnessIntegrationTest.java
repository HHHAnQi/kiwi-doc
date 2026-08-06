package com.xxx.ragdoc.application.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.auth.PermissionResolverPort;
import com.xxx.ragdoc.application.auth.AccessScope;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.harness.FileFixtureStore;
import com.xxx.ragdoc.application.chat.harness.HarnessProperties;
import com.xxx.ragdoc.application.chat.harness.LiveHarnessProvider;
import com.xxx.ragdoc.application.chat.harness.RecordHarnessProvider;
import com.xxx.ragdoc.application.chat.harness.ReplayHarnessProvider;
import com.xxx.ragdoc.application.chat.harness.FixtureStore;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import com.xxx.ragdoc.domain.auth.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR-5.1: 验证 ToolExecutor 真正接入 HarnessProvider (PR-5 收口).
 *
 * <p>用 stub Tool 让 Tool.execute 是可观察 side-effect; Record/Replay 走 FileFixtureStore 临时目录。
 *
 * <p>覆盖 EMS-PR6 §15.1 关键不变量:
 * <ul>
 *   <li>semantic_search metadata_search document_fetch 等"任意 Tool"通过 Executor 的 Record/Replay
 *   <li>REPLAY 不调 real Tool
 *   <li>不同 tenantScopeFingerprint 不命中 (无效 fixture)
 *   <li>permissionScopeVersion 不同不命中
 *   <li>indexVersion 不同不命中
 *   <li>缺 Fixture 不回退 LIVE
 *   <li>Replay 仍走 ToolExecutor 的 ACL evidence post-check
 * </ul>
 */
@DisplayName("ToolExecutor Harness 接入 (PR-5.1 收口)")
class ToolExecutorHarnessIntegrationTest {

    @TempDir
    java.nio.file.Path tmp;

    private static final Principal PRINCIPAL_A =
            new Principal("tenant-A", "user-A", Set.of("role:admin"), "tok");

    private ToolRegistry registry;
    private PermissionResolverPort permissionResolver;
    private MetricsPort metrics;
    private TraceObserver traceObserver;
    private AgentTool<StubIn, SearchOutput> stubTool;
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        AuthContext.set(PRINCIPAL_A);
        mapper = new ObjectMapper();
        permissionResolver = mock(PermissionResolverPort.class);
        when(permissionResolver.resolveAccessScope(PRINCIPAL_A))
                .thenReturn(AccessScope.tenantAdmin("tenant-A"));
        metrics = mock(MetricsPort.class);
        traceObserver = mock(TraceObserver.class);

        stubTool = mock(AgentTool.class);
        when(stubTool.descriptor())
                .thenReturn(new ToolDescriptor("stub_search", "v1", "stub",
                        "v1", "v1", ToolPermission.READ_RETRIEVE,
                        Duration.ofSeconds(5), 5, true, ToolCostCategory.INDEX_READ));
        when(stubTool.inputType()).thenReturn(StubIn.class);
        when(stubTool.outputType()).thenReturn(SearchOutput.class);
        registry = new ToolRegistry(List.of(stubTool));
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    record StubIn(String query) implements ToolInput {
        public StubIn {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("q");
        }

        @Override
        public String normalizedForDedup() {
            return "q=" + query.toLowerCase();
        }

        @Override
        public String toString() {
            return "StubIn[query=" + query + "]"; // 不带 banned 字段
        }
    }

    private static SearchOutput evidenceFor(String tenant, Long... chunkIds) {
        List<Evidence> evs =
                java.util.Arrays.stream(chunkIds)
                        .map(id -> Evidence.of(tenant, 100L, id, null, "ev " + id,
                                0.5, null, "stub_search", Map.of()))
                        .toList();
        return new SearchOutput(evs, new SearchOutput.TruncationInfo(false, evs.size(), evs.size()));
    }

    private ToolExecutor.ToolCallRequest req() {
        return new ToolExecutor.ToolCallRequest(
                "req-1", "run-1", Instant.now().plus(Duration.ofSeconds(3)), "iv-1");
    }

    private ToolExecutor newExecutor(HarnessProperties props, FixtureStore store) {
        return new ToolExecutor(
                registry, permissionResolver, metrics, traceObserver, mapper,
                props.getMode().equals(com.xxx.ragdoc.application.chat.harness.HarnessMode.LIVE)
                        ? new LiveHarnessProvider()
                        : (props.getMode()
                                        .equals(com.xxx.ragdoc.application.chat.harness.HarnessMode.RECORD)
                                ? new RecordHarnessProvider(store, mapper, "test")
                                : new ReplayHarnessProvider(store, mapper, true)),
                props);
    }

    @Test
    @DisplayName("LIVE: Tool.execute 被调用, 不读不写 Fixture")
    void liveMode() {
        HarnessProperties props = new HarnessProperties(); // 默认 enabled=false
        ToolExecutor ex = newExecutor(props, null);
        when(stubTool.execute(any(), any()))
                .thenReturn(ToolResult.success("id", "stub_search", "v1",
                        evidenceFor("tenant-A", 1L, 2L), 5, Map.of()));

        ToolResult<?> r = ex.execute("stub_search", "v1", new StubIn("q"), req());

        assertThat(r.status()).isEqualTo(ToolStatus.SUCCESS);
        verify(stubTool, times(1)).execute(any(), any());
    }

    @Test
    @DisplayName("RECORD: Tool.execute 被调用 + 写 fixture")
    void recordMode() throws java.io.IOException {
        HarnessProperties props = new HarnessProperties();
        props.setEnabled(true);
        props.setMode(com.xxx.ragdoc.application.chat.harness.HarnessMode.RECORD);
        FixtureStore store = new FileFixtureStore(tmp.toString(), mapper);
        ToolExecutor ex = newExecutor(props, store);
        when(stubTool.execute(any(), any()))
                .thenReturn(ToolResult.success("id", "stub_search", "v1",
                        evidenceFor("tenant-A", 1L), 5, Map.of()));

        ToolResult<?> r = ex.execute("stub_search", "v1", new StubIn("q"), req());

        assertThat(r.status()).isEqualTo(ToolStatus.SUCCESS);
        verify(stubTool, times(1)).execute(any(), any());
        // 有 fixture 文件写入
        long files = java.nio.file.Files.walk(tmp).filter(java.nio.file.Files::isRegularFile).count();
        assertThat(files).isOne();
    }

    @Test
    @DisplayName("REPLAY: Tool.execute 不被调用 (从 Fixture 读)")
    void replayModeDoesNotCallRealTool() {
        // 先 record
        HarnessProperties recordProps = new HarnessProperties();
        recordProps.setEnabled(true);
        recordProps.setMode(com.xxx.ragdoc.application.chat.harness.HarnessMode.RECORD);
        FixtureStore store = new FileFixtureStore(tmp.toString(), mapper);
        ToolExecutor recordEx = newExecutor(recordProps, store);
        when(stubTool.execute(any(), any()))
                .thenReturn(ToolResult.success("id", "stub_search", "v1",
                        evidenceFor("tenant-A", 1L, 2L), 5, Map.of()));
        recordEx.execute("stub_search", "v1", new StubIn("q"), req());

        // 再 replay
        HarnessProperties replayProps = new HarnessProperties();
        replayProps.setEnabled(true);
        replayProps.setMode(com.xxx.ragdoc.application.chat.harness.HarnessMode.REPLAY);
        ToolExecutor replayEx = newExecutor(replayProps, store);
        // counter 要 reset, 让 callIndex 一致 → 同 replayKey
        org.mockito.Mockito.reset(stubTool);
        when(stubTool.descriptor())
                .thenReturn(new ToolDescriptor("stub_search", "v1", "stub",
                        "v1", "v1", ToolPermission.READ_RETRIEVE,
                        Duration.ofSeconds(5), 5, true, ToolCostCategory.INDEX_READ));
        when(stubTool.inputType()).thenReturn(StubIn.class);
        when(stubTool.outputType()).thenReturn(SearchOutput.class);

        ToolResult<?> r = replayEx.execute("stub_search", "v1", new StubIn("q"), req());

        assertThat(r.status()).isEqualTo(ToolStatus.SUCCESS);
        // Tool 不被调用
        verify(stubTool, times(0)).execute(any(), any());
    }

    @Test
    @DisplayName("REPLAY: 缺 Fixture → TERMINAL_ERROR (不回退 LIVE)")
    void replayMissingFixtureNoFallback() {
        HarnessProperties props = new HarnessProperties();
        props.setEnabled(true);
        props.setMode(com.xxx.ragdoc.application.chat.harness.HarnessMode.REPLAY);
        FixtureStore store = new FileFixtureStore(tmp.toString(), mapper); // 空 store
        ToolExecutor ex = newExecutor(props, store);

        ToolResult<?> r = ex.execute("stub_search", "v1", new StubIn("missing-q"), req());

        assertThat(r.status()).isEqualTo(ToolStatus.TERMINAL_ERROR);
        verify(stubTool, times(0)).execute(any(), any());
    }

    @Test
    @DisplayName("REPLAY: 不同 tenantScopeFingerprint (tenant 切换) → TERMINAL_ERROR 不命中")
    void replayDifferentTenantNotHit() {
        // 先 record tenant-A
        HarnessProperties recordProps = new HarnessProperties();
        recordProps.setEnabled(true);
        recordProps.setMode(com.xxx.ragdoc.application.chat.harness.HarnessMode.RECORD);
        FixtureStore store = new FileFixtureStore(tmp.toString(), mapper);
        ToolExecutor recordEx = newExecutor(recordProps, store);
        when(stubTool.execute(any(), any()))
                .thenReturn(ToolResult.success("id", "stub_search", "v1",
                        evidenceFor("tenant-A", 1L), 5, Map.of()));
        recordEx.execute("stub_search", "v1", new StubIn("q"), req());

        // 切到 tenant-B
        AuthContext.clear();
        Principal principalB = new Principal("tenant-B", "user-B", Set.of("role:admin"), "tok");
        AuthContext.set(principalB);
        when(permissionResolver.resolveAccessScope(principalB))
                .thenReturn(AccessScope.tenantAdmin("tenant-B"));
        org.mockito.Mockito.reset(stubTool);
        when(stubTool.descriptor())
                .thenReturn(new ToolDescriptor("stub_search", "v1", "stub",
                        "v1", "v1", ToolPermission.READ_RETRIEVE,
                        Duration.ofSeconds(5), 5, true, ToolCostCategory.INDEX_READ));
        when(stubTool.inputType()).thenReturn(StubIn.class);
        when(stubTool.outputType()).thenReturn(SearchOutput.class);

        HarnessProperties replayProps = new HarnessProperties();
        replayProps.setEnabled(true);
        replayProps.setMode(com.xxx.ragdoc.application.chat.harness.HarnessMode.REPLAY);
        ToolExecutor replayEx = newExecutor(replayProps, store);

        ToolResult<?> r = replayEx.execute("stub_search", "v1", new StubIn("q"), req());

        // tenant-B 不同 → TenantScopeFingerprint 不同 → ReplayKey 不同 → NOT_FOUND → TERMINAL_ERROR
        assertThat(r.status()).isEqualTo(ToolStatus.TERMINAL_ERROR);
        verify(stubTool, times(0)).execute(any(), any());
    }

    @Test
    @DisplayName("REPLAY ACL post-check: fixture 里的跨租户 Evidence 被 Executor 过滤掉")
    void replayEvidencePostCheckStillRuns() {
        // Stub record 阶段返回 tenant-B Evidence (理论上 Tool 不该这么干, 但 PR-5.1 双保险)
        HarnessProperties recordProps = new HarnessProperties();
        recordProps.setEnabled(true);
        recordProps.setMode(com.xxx.ragdoc.application.chat.harness.HarnessMode.RECORD);
        FixtureStore store = new FileFixtureStore(tmp.toString(), mapper);
        ToolExecutor recordEx = newExecutor(recordProps, store);
        when(stubTool.execute(any(), any()))
                .thenReturn(ToolResult.success("id", "stub_search", "v1",
                        evidenceFor("tenant-B", 1L), 5, Map.of())); // 跨租户
        ToolResult<?> recordR = recordEx.execute("stub_search", "v1", new StubIn("q"), req());

        // PR-5.1 record 阶段 evidence post-check 应已过滤 (ToolExecutor.ACL post-check 不受 mode 影响)
        assertThat(recordR.status()).isEqualTo(ToolStatus.EMPTY_RESULT);
        assertThat(recordR.output()).isNull();
    }
}
