# V3 第 1 周 parser-service 拆分 — 设计 Spec

**作者**: 架构师评审产
**日期**: 2026-08-02
**状态**: Draft(待 review)
**关联**: ADR-0005(服务拆分范围 3 个) / ADR-0008(评价体系) / V3 DoD-1/2/4

---

## 0. TL;DR(管理者摘要)

把同步 5-15s 的 HTTP 上传 → Tika 解析链路, 改成**异步 MQ 驱动**: chat-app 202
立即返回(写入 documents + parse_tasks 后只发 MQ msg), 独立 parser-service 消费做
Tika+切片+embed+Milvus。命 V3 DoD-1(kill-9 优雅降级) + DoD-2(重试队列续解析) +
DoD-4(中断恢复续点)。total ~5-7 天工时, 3 commits。

---

## 1. 背景与目标

### 1.1 现状(代码已读)

| 文件 | 当前职责 | 痛点 |
|---|---|---|
| `interfaces/rest/DocumentController.uploadDoc()` | 接收 multipart 文件 | HTTP 线程被长期占用 |
| `application/document/DocumentUploadService.upload()` | 校验+幂等+上传 MinIO**+同步调** `ParsingTrigger` | RTT 5-15s |
| `infrastructure/parse/TikaParsingTrigger.trigger()` | 9 步同步链路: 下载→Tika→切片→embed→MySQL→Milvus→markReady | 单点中断需手动重传 |
| `application/document/ParsingTrigger`(port) | DDD 端口, 单实现 TikaParsingTrigger | 拆分利器 — 加新 MQTT impl 不破老逻辑 |

### 1.2 目标(V3 DoD 命中)

| DoD | 命中点 | 验证手段 |
|---|---|---|
| **DoD-1** kill -9 优雅降级 | parser 进程死, chat-app 上传仍 202 返回, 重启后续解析 | kill-9 演练脚本 |
| **DoD-2** 重试队列续解析 | retry_count + max_retries + DLQ + RocketMQ redelivery | 解析失败 max_retries 后入 DLQ |
| **DoD-4** 中断恢复续点 | `chunks_written` + `chunk_seq_offset` flush 到 parse_tasks, 重启续 | 中途 kill → restart 后查 chunks_written 不归零 |

### 1.3 非目标(明确不做)

- 不做 page-level PDF 续点(TikaParser 不易暴露 page progress; V3.5 接 PDFParser 改造)
- 不做 parser-service HTTP 入口(纯 MQ 消费)
- 不做 SSE 进度推送(V4 chat UI 才要)

---

## 2. 服务边界 + 调用契约

### 2.1 拓扑

```
   ┌─────────────────────────────────────┐
   │ chat-app (platform-bootstrap, 留单体)│
   │                                     │
   │  POST /documents → upload:           │
   │    1. validate + hash(SHA-256 file)  │
   │    2. INSERT documents UPLOADED      │
   │    3. INSERT parse_tasks PENDING     │
   │    4. produce MQ "parse-task-submit" │
   │    5. return 202 {doc_id, hash}      │
   │                                     │
   │  同时: chat / retrieve / feedback   │
   │  全部留单体不动                       │
   │                                     │
   └─────────────┬───────────────────────┘
                 │ MQ async only
                 ▼
   ┌─────────────────────────────────────┐
   │ parser-service (新, 独立 Spring Boot)│
   │                                     │
   │  consume MQ "parse-task-submit":    │
   │    1. UPDATE task RUNNING leased_by │
   │    2. MinIO download raw bytes     │
   │    3. TikaParsingTrigger.trigger()  │ ← 整体迁移自 chat-app
   │       内部: Tika+Chunk+Embed+MySQL+Milvus
   │    4. 每 10 chunks flush chunks_written ──┐
   │    5. UPDATE task PARSED + doc.markReady │ 中断位点
   │    6. produce "parse-task-result"   │
   └─────────────────────────────────────┘
```

### 2.2 契约(Queue Topics JSON)

#### `parse-task-submit`(chat-app → parser-service)
```json
{
  "taskId": 12345,
  "documentId": 678,
  "contentHash": "a3f8e1d2c4b5...",
  "submittedAt": "2026-08-02T10:15:30Z"
}
```

#### `parse-task-result`(parser-service → chat-app, V3 可选订阅)
```json
{
  "taskId": 12345,
  "documentId": 678,
  "status": "PARSED",            // 或 FAILED / CANCELLED
  "errorMessage": null,
  "chunksWritten": 47,
  "durationMs": 3400
}
```

### 2.3 灰度降级(plan 提, V3 第 1 周实现)

`RAG_PARSER_MODE=sync|async` (默认 async):
- **async**(生产): DocumentUploadService 走 MQ 路径
- **sync**(调试/降级): 走老 TikaParsingTrigger 同步路径(平滑迁移保护, 若 parser-service 故障可降级保可用)
- 由 Spring `@ConditionalOnProperty` 切换两个 ParsingTrigger impl

---

## 3. 数据模型 — `parse_tasks` 表(Flyway V5 migration)

### 3.1 DDL

```sql
-- V5__add_parse_tasks.sql
CREATE TABLE parse_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL COMMENT '幂等 key = doc.content_hash SHA-256',

    -- 状态机: PENDING → RUNNING → PARSED (或 FAILED → 重试 PENDING → ..., CANCELLED)
    status ENUM('PENDING','RUNNING','PARSED','FAILED','CANCELLED') NOT NULL DEFAULT 'PENDING',

    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3 COMMENT '达到后入 DLQ 不再调度',

    -- 中断位点(V3 续点: chunk-level)
    chunks_written INT NOT NULL DEFAULT 0 COMMENT '已成功落库 chunks 数',
    chunk_seq_offset INT NOT NULL DEFAULT 0 COMMENT '下次 chunk.seq 起始值',

    -- 失败信息
    error_message TEXT NULL,
    error_class VARCHAR(200) NULL,
    attempts JSON NULL COMMENT '每次 attempt 历史: timestamp/duration_ms/error',

    -- visibility timeout + lease 心跳(AWS SQS-style)
    visible_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        COMMENT '此时间之前不允许其他 worker 抢',
    leased_by VARCHAR(50) NULL COMMENT 'worker hostname+pid, 防同工抢',

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uk_parse_tasks_content_hash UNIQUE (content_hash),
    CONSTRAINT fk_parse_tasks_document FOREIGN KEY (document_id)
        REFERENCES documents(id) ON DELETE CASCADE,

    KEY idx_parse_status_visible (status, visible_at),
    KEY idx_parse_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='V3 parser-service 任务表 — 拆分异步 pipeline + 中断恢复';
```

### 3.2 状态机

```
                       insert(由 DocumentUploadService)
                              ↓
                          ┌────────┐
                       ┌──│PENDING │
                       │  └────┬───┘
                       │       │ worker pull(visible_at≤now)
                       │       ↓
                       │  ┌────────┐
                       │  │RUNNING │ ←─ 心跳 job 把 visible_at<now 的 RUNNING 回滚到 PENDING
                       │  └─┬──┬───┘   (kill -9 / OOM 后僵尸 worker 回收)
                       │    │  │
                       │    │  │ 解析失败
                       │    │  │ retry_count < max_retries
                       │    │  ↓
                       │    │ ┌────────┐
                       │    │ │FAILED  │
                       │    │ └────┬───┘
                       │    │      │ redelivery delay 60s → visible_at push
                       │    │      ↓
                       │    │   回 PENDING(retry_count++)
                       │    │
                       │    │ 解析成功
                       │    ↓
                       │  ┌────────┐
                       │  │PARSED  │ ← 终态
                       │  └────────┘
                       │
                       │ retry_count ≥ max_retries
                       ↓
                   ┌───────────┐
                   │CANCELLED  │ ← DLQ 终态(人工介入)
                   └───────────┘
```

### 3.3 状态迁移规则(代码层 invariant)

| from | to | 触发 | 守卫条件 |
|---|---|---|---|
| (init) | PENDING | INSERT by upload | content_hash 唯一约束拒绝重 |
| PENDING | RUNNING | worker pull | `visible_at ≤ NOW()` |
| RUNNING | PENDING | 心跳 job | `visible_at < NOW() AND status='RUNNING'` |
| RUNNING | PARSED | 解析成功 | chunks_written > 0 |
| RUNNING | FAILED | 异常 | retry_count++ |
| FAILED | PENDING | 重试 | `retry_count < max_retries` + visible_at push +60s |
| FAILED | CANCELLED | 中毒 | retry_count ≥ max_retries |
| PARSED/CANCELLED | (终态) | — | 不再迁移 |

---

## 4. 核心时序

### 4.1 上传 + 触发解析(异步 happy path)

```
Client        chat-app          MQ broker      parser-service    MySQL          MinIO   Milvus
  │              │                  │                 │             │              │       │
  │ POST /docs   │                  │                 │             │              │       │
  │ ───────────► │                  │                 │             │              │       │
  │              │ 1. validate/hash │                 │             │              │       │
  │              │ 2. INSERT doc ───────────────────────────────────►              │       │
  │              │ 3. MinIO upload ────────────────────────────────────────────────►       │
  │              │ 4. INSERT parse_tasks(UPLOADED→PENDING) ───────────►             │       │
  │              │ 5. producer.send ─►                 │             │              │       │
  │ 202 OK       │                  │ ──msg poll────► │             │              │       │
  │ ◄─────────── │                  │                 │             │              │       │
  │              │                  │                 │ 6. UPDATE RUNNING leased_by=host1 ──►
  │              │                  │                 │ 7. download  ───────────────────────►
  │              │                  │                 │ 8. Tika + chunk (每10 flush) ───►
  │              │                  │                 │ 9. embed     ─► BGE-M3 via http       │
  │              │                  │                 │ 10. INSERT chunks ─────────────────►
  │              │                  │                 │ 11. upsert Milvus ─────────────────────►
  │              │                  │                 │ 12. UPDATE PARSED + doc.markReady ──►
  │              │                  │ ◄──result msg── │             │              │       │
  │ (V4 SSE 时此 result 触发前端推送, V3 不订阅)                          │              │       │
```

### 4.2 kill -9 故障路径

```
T0: parser-service 消费 task=1, UPDATE RUNNING leased_by=host1, visible_at=now+5min
T1: parser kill -9, 没 ack msg, msg 在 broker 重回 queue
T2: 心跳 job 每 30s 扫: WHERE status='RUNNING' AND visible_at<now
    → 不命中(task visible_at 还在未来 5min 内)
T3+5min: visible_at 过期, 心跳 job 命中 → UPDATE status=PENDING
T4: 第二 worker / 重启后的 worker pull 到, 更新 leased_by=host2, 重跑
```

---

## 5. 故障模型(5 个场景)

### 5.1 fault mode 对应表

| Fault | 触发 | 期望行为 | 实现 |
|---|---|---|---|
| 1. parser 进程 kill -9 | 进程死, msg 没 ack | task 卡 RUNNING, 等 visible_at 过期, 心跳 job 回滚 PENDING | visible_at 字段 + `@Scheduled` job |
| 2. Tika throw(OOM/PDF 损坏) | 单 task 解析异常 | retry_count++, visible_at push +60s, 走重试; >max → DLQ | RocketMQ redelivery policy |
| 3. 中毒消息(同 task 反复失败) | retry_count ≥ max_retries | 标 CANCELLED 终态, 入 DLQ topic 不再调度 | `@Recover` + 死信表 |
| 4. 重传同 hash(幂等) | 上传时 content_hash 重复 | parse_tasks 唯一索引拒绝, 上层 catch DuplicateKeyException → 返原 doc_id | `UNIQUE uk_content_hash` |
| 5. update PARSED 但 Milvus upsert 失败 | 网络 timeout | saga 设计: chunks INSERT 与 Milvus upsert 分离, 失败Milvus → task 回 RUNNING + retry; chunks INSERT 是幂等的(同 doc 先 deleteByDocumentId 再 upsert) | 同方法 try-catch + 显式 status 回 |

### 5.2 边界条件

- **同 doc 重复解析**(老问题): chunks 先 deleteByDocumentId 再 INSERT(已有)
- **跨 worker 并发抢同 task**: visible_at 单语句 UPDATE 加 `WHERE status='PENDING' AND visible_at≤now` 让并发安全(只有 1 worker 能改)
- **RocketMQ broker 挂了**: chat-app 上传时 producer.send 同步 ACK, broker 挂 → 5xx; 这是 V3 接受的故障(chat-app 自己 fail fast)

---

## 6. 与其他服务 / 模块交互

### 6.1 V3 第 1 周末服务拓扑

```
                ┌─────────────┐
                │   MySQL     │ ← 共享(同步 schema)
                └──┬──────────┘
       ┌───────────┼─────────────┐
       ▼           ▼             ▼
  ┌─────────┐  ┌──────────┐  ┌──────────┐
  │chat-app │  │ MinIO    │  │ Milvus   │  ← 共享基础设施
  │(单体内) │  │ (storage)│  │(vectors) │
  │ upload   │  └──┬──────┘  └──┬───────┘
  │ chat     │     │            │
  │ retrieve │     │            │
  └────┬─────┘     │            │
       │           │            │
       ▼           ▼            ▼
   ┌─────────────────────────────┐
   │     RocketMQ broker          │ ← 新增中间件
   └──────────────┬───────────────┘
                  │ async
                  ▼
            ┌──────────┐
            │parser-svc│ ← V3 新增
            │ consumer │
            └──────────┘
```

### 6.2 后续(V3 第 2 周) rag-service / llm-gateway 拆完后

```
chat-app ──HTTP Feign──► rag-service
          ──HTTP Feign──► llm-gateway
          ──MQ──────────► parser-service(本期产出)
```

parser-service 不与 rag/llm 交互, 上下游完隔离。

### 6.3 与 ADR-0008(评价 CI 门禁) 协同

V3 第 1 周末 Commit 3 完成时, 用 ADR-0008 CI eval-regression 跑 curated 30 题,
对比 baseline 验证 **"拆 parser 无回归"** (RAGAS 指标变化应在 ±1.7pp 噪声内)。

---

## 7. 测试策略

### 7.1 单测覆盖(单模块)

- `ParseTaskService` 状态迁移: 全枚举组合矩阵(8 个迁移规则)
- `ParseTaskConsumer`: MQ 收到 → 调 TikaParsingTrigger → UPDATE 状态
- `TikaParsingTriggerChunked` 续点: 中途 abort → restart → 从 chunk_seq_offset 续
- `ParseTaskProducer.send` 同步 ACK / 失败重试
- `VisibilityTimeoutScheduler` 扫过期 RUNNING task

### 7.2 集成测试(Testcontainers)

- happy: chat-app 上传 → MQ → parser-service → MySQL/Milvus 一致
- kill -9 演练(集成测试版): Thread.sleep 模拟中途死 → restart 续
- poison message: mock TikaParsingTrigger 抛异常 max_retries 次 → DLQ 命中

### 7.3 kill -9 演练脚本(V3 DoD-1 硬资产)

`scripts/v3-kill-9-drill.sh`(commit 3):
```bash
#!/usr/bin/env bash
# 步骤:
# 1. 起 docker-compose(MySQL/MinIO/Milvus/RocketMQ)
# 2. 起 chat-app(port 8092) + parser-service(port 8093)
# 3. 后台上传 1 doc
# 4. sleep 1s; pkill -9 -f parser-service
# 5. 重启 parser-service; sleep 30s
# 6. 校验: parse_tasks 该 task = PARSED + chunks 表有该 doc 的行
# 7. exit 0/1 + 截图脚本
```

### 7.4 ADR-0008 CI 配合

PR label `eval-impact`: 跑 curated 30 题 smoke 对照 baseline, 任一指标降 >3pp 阻断合并。

---

## 8. 实施 3 步(3 commits)

### Commit 1 (V3 Day 1-2): Gradle module + DDL + 领域类

新增/修改文件:
- `settings.gradle.kts`: `include("parser-service")`
- `parser-service/build.gradle.kts`: Spring Boot 3.3.2 + rocketmq-client + depend on platform-common
- `parser-service/src/main/.../ParserServiceApplication.java`: `@SpringBootApplication` 启动类
- `platform-bootstrap/src/main/resources/db/migration/V5__add_parse_tasks.sql`: DDL
- `parser-service/src/main/java/com/xxx/ragdoc/parser/domain/ParseTask.java`: domain record
- `parser-service/src/main/java/com/xxx/ragdoc/parser/domain/ParseTaskStatus.java`: enum
- `parser-service/src/main/java/com/xxx/ragdoc/parser/application/ParseTaskService.java`: 状态迁移

(不动 chat-app 业务代码 — 仅新 module + DDL)

### Commit 2 (V3 Day 3-4): RocketMQ producer + consumer + 中断位点

修改 chat-app:
- `platform-bootstrap/build.gradle.kts`: 加 RocketMQ client starter
- `application/document/DocumentUploadService.upload()`: 末尾 INSERT parse_tasks + producer.send
- 新建 `infrastructure/mq/ParseTaskProducer.java`: RocketMQ template
- 配置 `RAG_PARSER_MODE`(`@ConditionalOnProperty` 切 sync/async impl)

迁移:
- 把 `infrastructure/parse/TikaParsingTrigger.java` 整体移到 `parser-service/`
- 加 chunk-level checkpoint: 每 10 chunks flush chunks_written 到 parse_tasks
- `parser-service` 加 `infrastructure/mq/ParseTaskConsumer.java`

### Commit 3 (V3 Day 5): kill -9 演练 + 心跳 + DLQ + 验收

- `parser-service` 加 `VisibilityTimeoutScheduler`: `@Scheduled` 每 30s 扫过期 RUNNING
- RocketMQ DLQ 配置(max_retries=3 后入 `%DLQ%parse-task-submit-consumer-group`)
- `scripts/v3-kill-9-drill.sh`: 跑一遍出截图/日志
- 跑 ADR-0008 CI eval-regression 验证拆 parser 无回归

---

## 9. DoD 验收清单

| DoD | 命中点 | 验证脚本 |
|---|---|---|
| DoD-1 kill -9 | parser 进程死, chat-app 仍 202 返回 | `scripts/v3-kill-9-drill.sh` |
| DoD-2 重试续解析 | retry_count 计数 + DLQ 测试 | 集成测试 mock Tika 失败 |
| DoD-4 中断续点 | chunk_seq_offset flush 续点 | 集成测试中途 abort + restart |

(DoD-3 p95<2s / DoD-5 trace / DoD-6 灰度在 V3 后续周命中)

---

## 10. Open Questions(留给 review 决策)

1. **RocketMQ vs Kafka vs RabbitMQ**: 选 RocketMQ(原 V3 文档规划), 但 Spring 社区 Kafka 生态更熟, 你倾向哪个?
2. **page_offset V3.5 接 PDFParser**: V3 不做 page-level 续点, 同意?
3. **DLQ 落库 vs direct log**: max_retries 后写 `parse_dlq` 表 vs 仅 RocketMQ 自带 DLQ topic 够不够? 倾向后者(V3 极简)。

---

## 11. 后续 V3 第 2-5 周(预告, 仅参)

- V3 第 2 周: rag-service + llm-gateway 拆(同步 HTTP Feign)
- V3 第 3 周: Langfuse + SSE 流式 + Semantic Cache
- V3 第 4 周: k3s on Autodl(ADR-0007)
- V3 第 5 周: Locust 压测 + kill -9 演练全套 + V3 验收报告
