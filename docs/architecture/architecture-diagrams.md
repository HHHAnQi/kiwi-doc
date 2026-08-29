# Architecture Diagrams — 五张核心架构图 (Phase 6)

> 2026-08-29 · 全部图基于真实代码结构绘制（模块名/数据流可在 `platform-bootstrap`/
> `parser-service` 源码中逐一定位），无虚构模块。

## 1. System Context Diagram

只回答外部边界：谁用系统、系统依赖谁。

```mermaid
flowchart LR
    User[用户 / React SPA] --> K[kiwi-doc<br/>rag-doc-platform]
    DS[文档来源<br/>PDF/DOCX/PPT/TXT] -->|上传| K
    K -->|chat / retrieve / agent-run API| User
    K --> MP[Model Provider<br/>glm-4-plus @ Zhipu<br/>+ DeepSeek 备路]
    K --> ST[Storage<br/>MySQL 事实源 / Milvus 派生索引<br/>MinIO 对象 / Redis 会话]
    K --> OBS[可观测<br/>Prometheus / Langfuse]
```

## 2. Layered Architecture

DDD 六边形四层（真实模块映射）：

```mermaid
flowchart TB
    subgraph L1 [Application Layer — interfaces/rest · application/*]
        CTRL[REST Controllers / SSE] --- ORCH[ChatOrchestrator / Pipeline Registry]
    end
    subgraph L2 [AI Orchestration Layer — application/chat]
        CLASSIC[Classic RAG Pipeline] --- AGENT[Planned Agent Runtime<br/>Planner/Sufficiency/Replan]
        AGENT --- MCPM[MCP Server 暴露<br/>rag_search / rag_ask]
    end
    subgraph L3 [Retrieval Layer — application/chat + infrastructure]
        RET[RetrieveService<br/>hybrid + RRF] --- RR[Rerank Client<br/>bge-reranker GPU]
        RET --- CTX[Token-Budget Context Builder]
    end
    subgraph L4 [Knowledge / Data Layer]
        MYSQL[(MySQL 事实源)] --- MILVUS[(Milvus 派生索引)]
        MINIO[(MinIO 对象)] --- REDIS[(Redis 会话)]
    end
    subgraph L5 [Infrastructure Layer — infrastructure/*]
        JPA[JPA/JDBC] --- MQC[RocketMQ producer]
        CBK[CircuitBreaker] --- TRC[TraceId/MDC/Langfuse]
        MET[Prometheus MetricsPort]
    end
    L1 --> L2 --> L3 --> L4
    L5 -.支撑.- L3
```

## 3. Online RAG Serving Flow

在线检索-生成主链（Classic 默认路径；ACL=回库校验，非 Milvus 内过滤完即止）：

```mermaid
flowchart LR
    Q[Query] --> QP[Query Processing<br/>多轮改写/历史压缩/话题检测]
    QP --> D[Dense BGE-M3] & S[Sparse BM25]
    D & S --> RRF[RRF Fusion]
    RRF --> ACL[ACL 回库校验<br/>租户/软删/generation]
    ACL --> RK[Cross-Encoder Rerank<br/>topN=5]
    RK --> CB[Context Budget<br/>双闸门+引用对齐]
    CB --> LLM[LLM 生成]
    LLM --> CV[Citation 对齐输出]
    CV --> R[Response + traceId]
```

## 4. Durable Ingestion Flow

异步索引链路（Phase 5 故障注入验证过的可靠机制全部标注）：

```mermaid
flowchart TB
    U[Upload] --> DB[("documents<br/>SHA256 幂等")]
    DB --> OB[Outbox 账本<br/>parse_tasks]
    OB -->|Relay| MQ[[RocketMQ]]
    MQ --> PS[parser-service]
    PS --> TK[Tika 抽取+脱敏+注入扫描]
    TK --> CH[结构感知切块<br/>flat/parent-child]
    CH --> EM[Contextual 前缀+Embedding]
    EM --> IDX[MySQL chunks +<br/>Milvus upsert(generation 隔离)]
    IDX --> OK[INDEXED ✓]
    PS -.Lease CAS 抢占.-> OB
    PS -.Retry×3→DLQ.-> MQ
    PS -.Checkpoint 每10 chunks.-> CH
    OB -.Visibility Timeout 回收.-> MQ
    DB -.幂等键.-> U
```

（Lease / Retry / Checkpoint / DLQ / Idempotency 五机制经 `docs/reliability/FAULT_INJECTION_REPORT.md` 真实注入验证。）

## 5. Evaluation Architecture

评测体系四层与回归门禁（全部真实存在于 `eval/` 与 `.github/workflows/`）：

```mermaid
flowchart TB
    DS[冻结数据集<br/>SHA256+commit 锁定]
    DS --> R1[检索侧<br/>Recall@K/MRR/NDCG]
    DS --> R2[生成侧<br/>RAGAS 四件套]
    DS --> R3[拒答分离<br/>自研: 诚实拒答≠幻觉]
    DS --> R4[Agentic 对照<br/>paired A/B+盲评换位<br/>+planner_source 隔离<br/>+bootstrap CI]
    R1 & R2 & R3 --> G1[CI -3pp 回归门禁<br/>eval-regression workflow]
    R4 --> G2[Validity Gate<br/>MODEL样本<80%禁结论]
    G1 & G2 --> BASE[冻结基线证书<br/>eval/baseline_*.md]
```
