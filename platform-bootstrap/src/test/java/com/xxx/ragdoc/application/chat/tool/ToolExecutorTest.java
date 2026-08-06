package com.xxx.ragdoc.application.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.auth.PermissionResolverPort;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import com.xxx.ragdoc.domain.auth.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PR-4: {@link ToolExecutor} 横切一致性测试 — ACL pre-check / dedup / banned fields / status mapping。
 *
 * <p>用一个 stub tool 让 AgentTool.execute 的行为可控; 真实 5 个 Tool 由各自 Test 覆盖语义正确性。
 */
@DisplayName("ToolExecutor - PR-4 横切逻辑")
class ToolExecutorTest {

    private static final Principal PRINCIPAL_ADMIN =
            new Principal("tenant-A", "admin-1", Set.of("role:admin"), "tok");
    private static final Principal PRINCIPAL_USER =
            new Principal("tenant-A", "user-1", Set.of("role:user"), "tok");

    private ToolRegistry registry;
    private PermissionResolverPort permissionResolver;
    private MetricsPort metrics;
    private TraceObserver traceObserver;
    private ToolExecutor executor;
    private AgentTool<StubIn, SearchOutput> stubTool;

    @BeforeEach
    void setup() {
        AuthContext.set(PRINCIPAL_ADMIN);
        permissionResolver = mock(PermissionResolverPort.class);
        // admin 路径: tenantAdmin=true, 无限制
        when(permissionResolver.resolveAccessScope(PRINCIPAL_ADMIN))
                .thenReturn(com.xxx.ragdoc.application.auth.AccessScope.tenantAdmin("tenant-A"));
        metrics = mock(MetricsPort.class);
        traceObserver = mock(TraceObserver.class);

        stubTool = mock(AgentTool.class);
        when(stubTool.descriptor())
                .thenReturn(
                        new ToolDescriptor(
                                "stub_search",
                                "v1",
                                "stub for executor test",
                                "v1",
                                "v1",
                                ToolPermission.READ_RETRIEVE,
                                Duration.ofSeconds(5),
                                5,
                                true,
                                ToolCostCategory.INDEX_READ));
        when(stubTool.inputType()).thenReturn(StubIn.class);
        when(stubTool.outputType()).thenReturn(SearchOutput.class);
        registry = new ToolRegistry(java.util.List.of(stubTool));
        // PR-5.1: ToolExecutor 现需 HarnessProvider + HarnessProperties (默认 LIVE → 与 PR-4 行为等价)
        com.xxx.ragdoc.application.chat.harness.HarnessProperties hp =
                new com.xxx.ragdoc.application.chat.harness.HarnessProperties();
        executor = new ToolExecutor(
                registry, permissionResolver, metrics, traceObserver,
                new ObjectMapper(),
                new com.xxx.ragdoc.application.chat.harness.LiveHarnessProvider(),
                hp);
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
    }

    private ToolExecutor.ToolCallRequest req(Duration remaining) {
        return new ToolExecutor.ToolCallRequest(
                "req-1", "run-1", Instant.now().plus(remaining), "iv-1");
    }

    private static SearchOutput outputWith(Long... chunkIds) {
        List<Evidence> evs =
                java.util.Arrays.stream(chunkIds)
                        .map(
                                id ->
                                        Evidence.of(
                                                "tenant-A",
                                                100L,
                                                id,
                                                null,
                                                "content " + id,
                                                0.5,
                                                null,
                                                "stub_search",
                                                java.util.Map.of()))
                        .toList();
        return new SearchOutput(evs, new SearchOutput.TruncationInfo(false, evs.size(), evs.size()));
    }

    @Nested
    @DisplayName("调用 + dedup")
    class InvokeAndDedup {

        @Test
        @DisplayName("同 runId+args 两次只执行一次 (SUCCESS 缓存)")
        void dedupSameArgsHitsCache() {
            when(stubTool.execute(any(), any()))
                    .thenReturn(
                            ToolResult.success(
                                    "ignored",
                                    "stub_search",
                                    "v1",
                                    outputWith(1L, 2L),
                                    5,
                                    java.util.Map.of()));

            StubIn in = new StubIn("hello");
            executor.execute("stub_search", "v1", in, req(Duration.ofSeconds(5)));
            ToolResult<?> r2 = executor.execute("stub_search", "v1", in, req(Duration.ofSeconds(5)));

            verify(stubTool, times(1)).execute(any(), any());
            assertThat(r2.metadata()).containsEntry("deduplicated", true);
        }

        @Test
        @DisplayName("不同参数 (query 大小写差异在 normalize 后等同) → 命中 cache")
        void dedupCaseInsensitiveHit() {
            when(stubTool.execute(any(), any()))
                    .thenReturn(
                            ToolResult.success("id", "stub_search", "v1", outputWith(1L), 5, java.util.Map.of()));
            executor.execute("stub_search", "v1", new StubIn("hello"), req(Duration.ofSeconds(5)));
            // normalize 后 "q=hello" 大小写不敏感
            executor.execute("stub_search", "v1", new StubIn("HELLO"), req(Duration.ofSeconds(5)));
            verify(stubTool, times(1)).execute(any(), any());
        }

        @Test
        @DisplayName("不同 runId 不共享 dedup")
        void differentRunNoCache() {
            when(stubTool.execute(any(), any()))
                    .thenReturn(
                            ToolResult.success("id", "stub_search", "v1", outputWith(1L), 5, java.util.Map.of()));
            executor.execute("stub_search", "v1", new StubIn("q"), req(Duration.ofSeconds(5)));
            // 不同 runId (req-2 / run-2)
            executor.execute(
                    "stub_search",
                    "v1",
                    new StubIn("q"),
                    new ToolExecutor.ToolCallRequest(
                            "req-2", "run-2", Instant.now().plus(Duration.ofSeconds(5)), "iv-1"));
            verify(stubTool, times(2)).execute(any(), any());
        }

        @Test
        @DisplayName("TIMEOUT 状态不被缓存 → 重复调每次都重试")
        void timeoutNotCached() {
            when(stubTool.execute(any(), any()))
                    .thenReturn(
                            ToolResult.failure(
                                    "id",
                                    "stub_search",
                                    "v1",
                                    ToolStatus.TIMEOUT,
                                    ToolError.of("TOOL_TIMEOUT", "timeout"),
                                    5,
                                    java.util.Map.of()));
            executor.execute("stub_search", "v1", new StubIn("q"), req(Duration.ofSeconds(5)));
            executor.execute("stub_search", "v1", new StubIn("q"), req(Duration.ofSeconds(5)));
            verify(stubTool, times(2)).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("ACL pre-check")
    class AclPreCheck {

        @Test
        @DisplayName("普通用户 NO_RECALL sentinel (空 allowedDocIds) → PERMISSION_DENIED, 不调 Tool")
        void emptyAllowedDocIdsBlocksCall() {
            AuthContext.set(PRINCIPAL_USER);
            when(permissionResolver.resolveAccessScope(PRINCIPAL_USER))
                    .thenReturn(
                            com.xxx.ragdoc.application.auth.AccessScope.of("tenant-A", Set.of()));

            ToolResult<?> r =
                    executor.execute(
                            "stub_search", "v1", new StubIn("q"), req(Duration.ofSeconds(5)));

            assertThat(r.status()).isEqualTo(ToolStatus.PERMISSION_DENIED);
            verify(stubTool, times(0)).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("Banned 字段检测")
    class BannedFields {

        @Test
        @DisplayName("input 含 tenantId 字段 → INVALID_ARGUMENT, 不调 Tool")
        void inputTenantIdRejected() {
            // 自定义 input record 含 banned 字段
            record BannedIn(String query, String tenantId) implements ToolInput {
                @Override
                public String normalizedForDedup() {
                    return "q=" + query + "|tenantId=" + tenantId;
                }

                @Override
                public String toString() {
                    return "BannedIn[query=" + query + ", tenantId=" + tenantId + "]";
                }
            }
            // 用一个新的 stub tool 带泛型 BannedIn
            AgentTool<BannedIn, ToolOutput> t = mock(AgentTool.class);
            when(t.descriptor())
                    .thenReturn(
                            new ToolDescriptor(
                                    "banned_test",
                                    "v1",
                                    "test",
                                    "v1",
                                    "v1",
                                    ToolPermission.READ_RETRIEVE,
                                    Duration.ofSeconds(5),
                                    5,
                                    true,
                                    ToolCostCategory.INDEX_READ));
            when(t.inputType()).thenReturn(BannedIn.class);
            when(t.outputType()).thenReturn((Class<ToolOutput>) (Class<?>) StubOutputMarker.class);
            ToolExecutor ex =
                    new ToolExecutor(
                            new ToolRegistry(java.util.List.of(t)),
                            permissionResolver,
                            metrics,
                            traceObserver,
                            new ObjectMapper(),
                            new com.xxx.ragdoc.application.chat.harness.LiveHarnessProvider(),
                            new com.xxx.ragdoc.application.chat.harness.HarnessProperties());

            ToolResult<?> r =
                    ex.execute("banned_test", "v1", new BannedIn("q", "tenant-A"), req(Duration.ofSeconds(5)));

            assertThat(r.status()).isEqualTo(ToolStatus.INVALID_ARGUMENT);
            verify(t, times(0)).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("Timeout / Tool missing")
    class TimeoutAndMissing {

        @Test
        @DisplayName("已到 deadline → TIMEOUT, 不调 Tool")
        void expiredDeadline() {
            ToolResult<?> r =
                    executor.execute(
                            "stub_search",
                            "v1",
                            new StubIn("q"),
                            req(Duration.ofMillis(-1))); // deadline 已过
            assertThat(r.status()).isEqualTo(ToolStatus.TIMEOUT);
            verify(stubTool, times(0)).execute(any(), any());
        }

        @Test
        @DisplayName("未注册 Tool → TERMINAL_ERROR (ToolResult failure)")
        void unregisteredToolTerminal() {
            ToolResult<?> r =
                    executor.execute("nonexistent", "v1", new StubIn("q"), req(Duration.ofSeconds(5)));
            assertThat(r.status()).isEqualTo(ToolStatus.TERMINAL_ERROR);
        }
    }

    /** ACL evidence post-check: Tool 返回的跨租户 Evidence 被过滤。 */
    @Test
    @DisplayName("ACL post-check: Tool 返回 tenant-B Evidence 被 drop (转 EMPTY_RESULT)")
    void evidencePostCheckFiltersCrossTenant() {
        SearchOutput cross =
                new SearchOutput(
                        List.of(
                                Evidence.of("tenant-B", 1L, 1L, null, "secret", 0.5, null, "x", java.util.Map.of()),
                                Evidence.of("tenant-A", 1L, 2L, null, "ok", 0.5, null, "x", java.util.Map.of())),
                        new SearchOutput.TruncationInfo(false, 2, 2));
        when(stubTool.execute(any(), any()))
                .thenReturn(ToolResult.success("id", "stub_search", "v1", cross, 5, java.util.Map.of()));

        ToolResult<SearchOutput> r =
                executor.execute("stub_search", "v1", new StubIn("q"), req(Duration.ofSeconds(5)));

        // Admin principal tenant=A, tenant-B 那条被过滤; 留下 tenant-A 一条
        assertThat(r.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(r.output().evidences()).hasSize(1);
        assertThat(r.output().evidences().get(0).tenantId()).isEqualTo("tenant-A");
        assertThat(r.metadata()).containsEntry("acl_dropped_unauthorized", 1);
    }

    /** 输出全跨租户 → EMPTY_RESULT (不和 SUCCESS 矛盾)。 */
    @Test
    @DisplayName("ACL post-check: 全部跨租户 → EMPTY_RESULT")
    void evidencePostCheckAllCrossTenant() {
        SearchOutput cross =
                new SearchOutput(
                        List.of(
                                Evidence.of("tenant-B", 1L, 1L, null, "secret", 0.5, null, "x", java.util.Map.of())),
                        new SearchOutput.TruncationInfo(false, 1, 1));
        when(stubTool.execute(any(), any()))
                .thenReturn(ToolResult.success("id", "stub_search", "v1", cross, 5, java.util.Map.of()));

        ToolResult<?> r = executor.execute("stub_search", "v1", new StubIn("q"), req(Duration.ofSeconds(5)));

        assertThat(r.status()).isEqualTo(ToolStatus.EMPTY_RESULT);
        assertThat(r.output()).isNull();
    }

    record StubOutputMarker() implements ToolOutput {}
}
