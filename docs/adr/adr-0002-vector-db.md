# ADR-0002: 向量库选型 — Milvus 2.5+

- Status: Accepted
- Date: 2026-07-27

## Context

RAG 系统的核心存储为向量库。约束：

1. **私有化部署**：不能使用 SaaS 向量服务。
2. **中文 RAG**：需 dense + sparse 双路检索（BGE-M3 配套）。
3. **扩展性**：从 V1 万级文档到 V4 千万级。
4. **行级权限**：多租户 + 部门级过滤，需强标量过滤。
5. **运维可控**：私有化交付运维成本可接受。

## Decision

选用 Milvus 2.5+ 作为向量库。

核心理由：原生支持向量 + sparse（BM25 类）+ 标量过滤，一个组件覆盖双路召回 + 权限过滤，避免外挂 Elasticsearch；分布式架构支持水平扩展。

## Alternatives Considered

| 方案 | 分布式 | BM25/sparse | 标量过滤 | 私有化 | 中文社区 | 运维成本 | 结论 |
|---|---|---|---|---|---|---|---|
| **Milvus 2.5+** | ✅ 原生 | ✅ 原生 | ✅ 强 | ✅ | ✅ | ⚠️ 中 | **采纳** |
| pgvector | ❌ 依赖 PG | ❌ 外挂 | ✅ SQL | ✅ | ✅ | ✅ 低 | 备选（小规模退化） |
| Qdrant | ✅ | ❌ 外接 | ✅ | ✅ | ⚠️ | ✅ 低 | 否决 |
| Redis VectorStore | ❌ | ❌ | ⚠️ | ✅ | ✅ | ✅ | 强烈否决 |
| Elasticsearch | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ 高 | 否决（资源重） |

## Consequences

+ 双路召回（向量 + BM25）统一在 Milvus 内，简化中间件栈。
+ 行级权限用标量过滤实现 `tenant_id + dept_id`。
+ 分布式分片支持 V4 千万级演进。
+ 索引可调优（HNSW 召回 < 60ms）。

- 多一套中间件，私有化交付额外运维（etcd / MinIO 依赖）。
- 版本迭代快，SDK 升级有兼容成本。

缓解：开发用 standalone（docker-compose），生产用 K8s cluster；`VectorStoreRepository` 抽象接口隔离 SDK，未来可切 pgvector / Qdrant。

## Revisit

- 文档量长期 < 10 万 + 无分布式需求 → 退化为 pgvector。
- Qdrant 加入原生 BM25 且中文社区成熟 → 重新对比。
