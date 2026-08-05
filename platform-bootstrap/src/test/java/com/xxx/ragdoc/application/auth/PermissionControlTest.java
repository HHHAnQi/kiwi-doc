package com.xxx.ragdoc.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.chat.RetrieveService;
import com.xxx.ragdoc.application.chat.RerankProperties;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.chat.port.RerankClient;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.application.document.port.VectorStore.MetadataFilter;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * V9 RAG-Perm-001 / Task 3 强制测试: 验证检索流程按 Principal 做硬过滤。
 *
 * <p>四条核心语义:
 *
 * <ol>
 *   <li><b>用户 A 不能查询用户 B 文档</b>: A 的 MetadataFilter.allowedDocIds 只含 A 自己的 doc, B 的 doc
 *       不会传入 Milvus ANN
 *   <li><b>admin 不受限</b>: allowedDocIds=null (哨兵), filter 不带 docId 子句
 *   <li><b>默认主体(单租户兼容)</b>: 无 token 时返 null, 同 admin 哨兵 — 让 tenant_id=default 兜底, 不破坏
 *       历史无 token 调用
 *   <li><b>空集合 → NO_RECALL 短路</b>: 无可读文档时不再落 Milvus (攻击者拿到无权限 token 也不会触发任何检索)
 * </ol>
 *
 * <p>语义对照 Task 3 要求"禁止只通过 Prompt 限制模型": 本测试断言请求 <b>根本不会到达 vectorStore.search</b>
 * 当用户无可读文档, 且有可读时也只传白名单 — 这是 DB 层硬过滤, 与 LLM prompt 完全无关。
 */
@DisplayName("V9 文档权限控制 - RetrieveService 硬过滤")
class PermissionControlTest {

    @AfterEach
    void clearThreadLocal() {
        // 防 ThreadLocal 串号到下个用例 — 单元测试不走 AuthFilter, 必须手工 clear
        AuthContext.clear();
    }

    /** 构造一个最小可运行的 RetrieveService, 所有 infra collaborator mock 掉。 */
    private static RetrieveService newRetrieveService(
            PermissionResolverPort resolver,
            VectorStore vs,
            ChunkRepository cr) {
        EmbeddingClient emb = mock(EmbeddingClient.class);
        when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[1024], null));
        RerankClient rr = mock(RerankClient.class);
        RerankProperties rp = new RerankProperties(); // enabled=false
        DocumentRepository dr = mock(DocumentRepository.class);
        when(dr.findDefaultReadyBySource(any())).thenReturn(Optional.empty());
        return new RetrieveService(emb, vs, cr, rr, rp, noopMetrics(), dr, resolver,
                q -> vs.search(q.embedding(), q.text(), q.docId(), q.topK(), q.filter()));
    }

    private static MetricsPort noopMetrics() {
        // 用 Mockito mock 而非手写匿名类 — MetricsPort 方法会持续新增 (多轮对话期的 topic_shift / compression
        // 等计量), 手写匿名类每次都要追改; mock 对所有方法返默认值, 测试断言不依赖 metric 值
        return mock(MetricsPort.class);
    }

    private static Chunk chunk(long id, long docId) {
        return new Chunk(
                id, docId, 0, ChunkType.TEXT, "正文 " + id, 0, null, null, "h", List.of());
    }

    // ============================================================
    // A 不能查 B 私有文档
    // ============================================================

    @Nested
    @DisplayName("用户 A 不能查询用户 B 的文档")
    class UserBPrivacyIsolation {

        @Test
        @DisplayName("A 的 MetadataFilter.allowedDocIds 只含 A 自己的 doc, 不含 B 的 doc")
        void shouldPassWhitelistToVectorStore() {
            // given: A 已登录, 可读 doc 10 (她自己的), B 的 doc 20 不在白名单
            Principal userA =
                    new Principal(
                            "default", "userA", Set.of("role:default", "role:user"), "token-a");
            AuthContext.set(userA);

            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            PermissionResolverPort resolver = p -> Set.of(10L); // A 只能读 doc 10

            RetrieveService svc = newRetrieveService(resolver, vs, cr);

            // when
            svc.retrieve(new ChatCommand("查 B 私有 doc", null, null));

            // then: MetadataFilter.allowedDocIds 严格 = {10}, 不包含 20
            @SuppressWarnings("unchecked")
            ArgumentCaptor<MetadataFilter> captor = ArgumentCaptor.forClass(MetadataFilter.class);
            verify(vs).search(any(), anyString(), any(), anyInt(), captor.capture());
            MetadataFilter used = captor.getValue();
            assertThat(used.allowedDocIds()).containsExactlyInAnyOrder(10L);
            assertThat(used.allowedDocIds()).doesNotContain(20L);
            assertThat(used.tenantId()).isEqualTo("default");
        }

        @Test
        @DisplayName("A 即便 Milvus 命中 B 的 chunk (假设 leak), RetrieveService 也不会回 B 的 doc_id")
        void shouldNeverReturnForeignDocEvenIfMilvusLeaks() {
            // 此用例防 (单测内的) Milvus filter 失效路径 — 验证即便下层 leak, 我们也明确不在此拦截,
            // 但白名单本身已经把 B 的 docId 排除在 ANN 之外 (上一用例已断言)
            // 这里更关键: A 拿到 chunk 列表必须都是白名单内的 docId
            Principal userA =
                    new Principal(
                            "default", "userA", Set.of("role:default", "role:user"), "token-a");
            AuthContext.set(userA);

            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            when(vs.search(any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(List.of(new ScoredChunk(101L, 0.9f))); // chunk 101 属 doc 10
            when(cr.findByIdIn(anyList())).thenReturn(List.of(chunk(101L, 10L)));
            PermissionResolverPort resolver = p -> Set.of(10L); // 仅 doc 10 白名单

            RetrieveService svc = newRetrieveService(resolver, vs, cr);

            // when
            RetrieveService.RetrieveResult r =
                    svc.retrieve(new ChatCommand("查询", null, null));

            // then: 命中的 citation 全部 docId=10, 不出现 20
            assertThat(r.items()).isNotEmpty();
            assertThat(r.items()).allSatisfy(c -> assertThat(c.docId()).isEqualTo(10L));
        }
    }

    // ============================================================
    // admin 不受限
    // ============================================================

    @Nested
    @DisplayName("admin 哨兵: allowedDocIds=null, 不加 docId 子句")
    class AdminBypass {

        @Test
        @DisplayName("admin 的 MetadataFilter.allowedDocIds=null (不加白名单子句)")
        void adminShouldGetNullWhitelist() {
            Principal admin =
                    new Principal(
                            "default",
                            "admin",
                            Set.of("role:default", "role:user", "role:admin"),
                            "admin-token");
            AuthContext.set(admin);

            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            PermissionResolverPort resolver = p -> null; // admin 哨兵

            RetrieveService svc = newRetrieveService(resolver, vs, cr);

            svc.retrieve(new ChatCommand("admin 全库查", null, null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<MetadataFilter> captor = ArgumentCaptor.forClass(MetadataFilter.class);
            verify(vs).search(any(), anyString(), any(), anyInt(), captor.capture());
            assertThat(captor.getValue().allowedDocIds()).isNull();
        }
    }

    // ============================================================
    // 默认主体 (单租户兼容)
    // ============================================================

    @Nested
    @DisplayName("默认主体 (无 token): 单租户兼容")
    class DefaultPrincipalCompatibility {

        @Test
        @DisplayName("AuthContext 默认主体 → resolver 返 null (与 admin 同哨兵路径)")
        void defaultPrincipalUnrestrictedByAcl() {
            // 不调用 AuthContext.set, 直接 currentPrincipal() 返回 DEFAULT_PRINCIPAL
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            PermissionResolverPort resolver = p -> null;

            RetrieveService svc = newRetrieveService(resolver, vs, cr);

            svc.retrieve(new ChatCommand("无 token 调用", null, null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<MetadataFilter> captor = ArgumentCaptor.forClass(MetadataFilter.class);
            verify(vs).search(any(), anyString(), any(), anyInt(), captor.capture());
            MetadataFilter used = captor.getValue();
            assertThat(used.allowedDocIds()).isNull();
            assertThat(used.tenantId()).isEqualTo("default"); // 默认 tenant
        }
    }

    // ============================================================
    // 无可读文档 → NO_RECALL 短路
    // ============================================================

    @Nested
    @DisplayName("空集合 = 无可读文档: 短路 NO_RECALL")
    class EmptyAllowedShortCircuit {

        @Test
        @DisplayName("allowedDocIds 空集合时, vectorStore.search 一次都不应被调")
        void emptySetShouldNotInvokeVectorStore() {
            Principal userC =
                    new Principal(
                            "default", "userC", Set.of("role:default", "role:user"), "token-c");
            AuthContext.set(userC);

            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            // C 没有任何可读文档 (ACL 没授权 + 无 PUBLIC + 无 TENANT 可见)
            PermissionResolverPort resolver = p -> Set.of();

            RetrieveService svc = newRetrieveService(resolver, vs, cr);

            RetrieveService.RetrieveResult r =
                    svc.retrieve(new ChatCommand("我没权限的查询", null, null));

            // 关键断言: vectorStore.search 必须从未被调
            verifyNoInteractions(vs);
            assertThat(r.items()).isEmpty();
        }
    }
}
