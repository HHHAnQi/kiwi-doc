package com.xxx.ragdoc.infrastructure.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link JpaChunkRepository} 集成测试。
 *
 * <p>Phase 0 工程债补全: 之前只有 Mockito 单测(mock ChunkJpaRepository), 无法验证:
 *
 * <ul>
 *   <li>JPQL {@code findActiveById} / {@code findByIdIn} 等查询真在 MySQL 8 上跑通
 *   <li>{@code saveAll} + {@code deleteByDocumentId} 的幂等性(Parent-Child 两阶段写入的关键正确性)
 *   <li>{@code findByIdIn} 批量查询(Phase 0.3 N+1 改造的回归保护)
 * </ul>
 *
 * <p>设计原则(项目测试策略 docs/engineering/testing.md §2.2): <b>不用 H2 替代</b>, 因为 MySQL 的 JSON 列 / charset /
 * FK 行为 H2 永远验不出。Testcontainers 起真 MySQL 8.4。
 *
 * <p>切片选择: 用 {@code @DataJpaTest} 仅起 JPA 切片, 切除 Milvus / MinIO / Embedding / LLM / Web
 * 这些启动期强依赖外部服务的 Bean。否则 @SpringBootTest 会触发 MilvusCollectionInitializer (ApplicationRunner) 尝试建
 * collection, 在无 Milvus 的环境必失败。
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// @DataJpaTest 默认只扫 @Repository; JpaChunkRepository 是 @Component, 需显式 import
@Import({JpaChunkRepository.class, ChunkMapper.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JpaChunkRepositoryIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("ragdoc_it")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        // 用 Flyway 建表(V1+V3 migration), 让 schema 与生产一致; Hibernate 仅 validate
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }

    @Autowired private JpaChunkRepository chunkRepository;
    @Autowired private TestEntityManager em;

    @Test
    @DisplayName("saveAll 幂等性: 同一 documentId 两次 saveAll, 不产生重复 chunks")
    void saveAllShouldBeIdempotentByDocumentId() {
        // given: 先存 documents 行(chunks 有 FK document_id → documents.id)
        Long docId = persistSampleDoc();

        Chunk c1 =
                new Chunk(
                        null,
                        docId,
                        0,
                        ChunkType.TEXT,
                        "段落 A 内容",
                        0,
                        null,
                        null,
                        "hash-a",
                        List.of());
        Chunk c2 =
                new Chunk(
                        null,
                        docId,
                        1,
                        ChunkType.TEXT,
                        "段落 B 内容",
                        0,
                        null,
                        null,
                        "hash-b",
                        List.of());

        // when: 第一次
        List<Chunk> saved1 = chunkRepository.saveAll(docId, List.of(c1, c2));
        em.flush();
        em.clear();
        long count1 = chunkRepository.countByDocumentId(docId);

        // then
        assertThat(saved1).hasSize(2);
        assertThat(count1).isEqualTo(2);

        // when: 第二次(saveAll 内部需先 deleteByDocumentId)
        Chunk c3 =
                new Chunk(
                        null,
                        docId,
                        0,
                        ChunkType.TEXT,
                        "段落 C 重写",
                        0,
                        null,
                        null,
                        "hash-c",
                        List.of());
        chunkRepository.saveAll(docId, List.of(c3));
        em.flush();
        em.clear();
        long count2 = chunkRepository.countByDocumentId(docId);

        // then: 不应 c1/c2 + c3 凑 3 条, 必须只剩 c3
        assertThat(count2).as("同文档重复 saveAll 必须先清旧再写").isEqualTo(1);
    }

    @Test
    @DisplayName("findByIdIn 批量查询: 一次 SQL 拉回多个 chunks(N+1 改造的回归保护)")
    void findByIdInShouldReturnAllInOneBatch() {
        long docId = persistSampleDoc();
        Chunk a = new Chunk(null, docId, 0, ChunkType.TEXT, "A", 0, null, null, "h-a", List.of());
        Chunk b = new Chunk(null, docId, 1, ChunkType.TEXT, "B", 0, null, null, "h-b", List.of());
        Chunk c = new Chunk(null, docId, 2, ChunkType.TEXT, "C", 0, null, null, "h-c", List.of());
        List<Chunk> saved = chunkRepository.saveAll(docId, List.of(a, b, c));
        em.flush();
        em.clear();

        List<Long> ids = saved.stream().map(Chunk::id).toList();
        List<Chunk> got = chunkRepository.findByIdIn(ids);

        assertThat(got).extracting(Chunk::id).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(got).allMatch(ch -> ch.content().matches("[ABC]"));
    }

    @Test
    @DisplayName("findById 单条查询路径不受改造影响(回归保护)")
    void findByIdStillWorks() {
        long docId = persistSampleDoc();
        Chunk saved =
                chunkRepository
                        .saveAll(
                                docId,
                                List.of(
                                        new Chunk(
                                                null,
                                                docId,
                                                0,
                                                ChunkType.TEXT,
                                                "唯一一条",
                                                0,
                                                null,
                                                null,
                                                "h-1",
                                                List.of())))
                        .get(0);
        em.flush();
        em.clear();

        Optional<Chunk> got = chunkRepository.findById(saved.id());
        assertThat(got).isPresent();
        assertThat(got.get().content()).isEqualTo("唯一一条");
    }

    /** 用原生 SQL 插一行 documents(chunks 表 FK 依赖), 避开 domain Document 装配。 */
    private Long persistSampleDoc() {
        // snake_case 列名与 Flyway V1__initial_schema + V3__add_documents_metadata 一致
        jakarta.persistence.Query q =
                em.getEntityManager()
                        .createNativeQuery(
                                "INSERT INTO documents (content_hash, original_filename,"
                                        + " mime_type, size_bytes, status, tenant_id, source,"
                                        + " version, language, doc_type) VALUES (?,?,?,?,?,'default'"
                                        + ", 'nacos', '2.4', 'zh', 'doc')");
        q.setParameter(1, "hash-" + System.nanoTime());
        q.setParameter(2, "it-test.pdf");
        q.setParameter(3, "application/pdf");
        q.setParameter(4, 100L);
        q.setParameter(5, "PARSING");
        q.executeUpdate();
        em.flush();
        // IDENTITY 生成的 id 回查
        Number id =
                (Number)
                        em.getEntityManager()
                                .createNativeQuery("SELECT LAST_INSERT_ID()")
                                .getSingleResult();
        return id.longValue();
    }
}
