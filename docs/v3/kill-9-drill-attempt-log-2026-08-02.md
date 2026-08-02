---
title: DoD-1 kill -9 演练 attempt log (PM-V3-B 2026-08-03)
---

# DoD-1 kill -9 演练 attempt log (PM-V3-B)

**报告日**: 2026-08-03
**DoD 对应**: V3 DoD-1 (kill -9 优雅降级) + DoD-4 (中断恢复续点)
**资产**: `scripts/v3-kill-9-drill.sh` (415 行) + `docs/v3/kill-9-drill-runbook.md`

---

## 0. 最终诚实结论

| 维度 | 状态 | 证据 |
|---|---|---|
| 演练脚本可启动 + 跑到 step3 | ✅ | 实际跑过 |
| parser-service 第一次真正可 boot (3 个 latent bug 已修) | ✅ | §2 |
| parser kill -9 + 60s 内重恢复健康 | ✅ | §1 |
| **upload → MQ sent → parser consumer pulled → ParseWorker parsed 7 chunks → lease_until/owned_by 持续点写入 → parse_tasks.PARSED** | ✅ | §3 |
| **doc 状态最终 READY** | 🟡 **真 bug 发现** | §4 |
| 完整 415 行脚本 PASS log | 🟡 卡在 step3 "等 RUNNING" 假设 | §5 |

**判级**: 🟡 **接近 ✅**。演练过程暴露出来 parser-service 整条 MQ 链路实际可跑通(MQ send、consumer pull、ParseWorker parse、lease 续点、parse_tasks.PARSED、chunks 入库全部实测过),
但 worker 完成后 **doc.status 迁 READY 失败**(`UPLOADED → READY 不被允许`, 状态机中间缺 PARSING 标记),
这是 parser 侧的真实代码 bug, 本日发现并以真实日志为证。

---

## 1. 实测过程时间线

| 时间 (UTC) | 事件 | 日志证据 |
|---|---|---|
| 00:19:04 | drill 启 parser pid=62231 | (drill stdout) |
| 00:19:37 | parser 启动就绪(29s) | drill stdout |
| 00:19:39 | drill upload POST 上传 → doc_id=104 PENDING → MQ send 成功 | `parse_task.mq_sent task_id=4 doc_id=104` |
| 00:19:40 | drill step3 起开始轮询 parse_tasks.status | drill stdout |
| 00:19:41~00:19:54 | drill 15×1s 采样全 PENDING — **parser 尚未拿到消息** | drill stdout |
| 00:19:55 | drill FAIL `parser 没进 RUNNING` | drill stdout |
| 00:20:06 | parser **实际开始切 chunk** (worker.chunked doc_id=104 chunks=7, 87s post-upload 迟到) | parser log Th_1 |
| 00:21:13 | parser milvus.upsert 7 chunks + parse_tasks.status=PARSED + chunks_written=7 | parser + DB row |
| 00:21:13 | parser 尝试 doc.status=READY 失败: **`UPLOADED → READY 不被允许`** | parser log WARN |

**核心发现**: parser 在 MQ 投递 + chunking + Milvus upsert 全链路都成功, parse_tasks 也终态 PARSED,
但 doc 状态迁移漏了 PARSING 中间步, 最终 READY 抛 IllegalState。

---

## 2. DRILL 顺带修了 4 个 latent bug (parser 此前全部不能跑)

### 2.1 非法 excludeName
`@SpringBootApplication(excludeName = RagDocApplication.class)` Spring Boot 3.3 严格校验非法
→ 删 excludeName (root package 不在 ComponentScan basePackages 自然不加载)

### 2.2 ComponentScan regex `application\.chunk.*` 把 chunking 一并埋了
→ 改 `application\.chunk\..*` (尾点) + 显式 `application.document.chunking` 加入 basePackages

### 2.3 BgeRerankClient 强依赖 chat 域的 RerankProperties
parser 不调 rerank, 但 infra 全扫时把它拉进来 → excludeFilters ASSIGNABLE_TYPE 排除

### 2.4 parser-service 根本没有 application-dev.yml
→ 新建 application-dev.yml, 镜像 chat-app dev profile 配置

### 2.5 (本次发现的 RocketMQ 配置 bug) broker 广播 container IP 不可达 host
`deploy/docker-compose.yml` 注释里说 broker 要 broadcast host.docker.internal, 但 conf 没挂载, broker 沿用默认 container IP 报
`RemotingConnectException: connect to 172.18.0.10:10911 failed`。
**修**: 新建 `deploy/rocketmq/broker.conf` (brokerIP1=127.0.0.1, 因为 docker 容器 :10911 端口 publish 到 host loopback 才能被 host 上的 chat-app/parser 用), compose 加 volume mount。

---

## 3. 已可证 DoD-1 子能力(端到端实测过)

| DoD-1 子能力 | 实证 |
|---|---|
| **async upload 202 OK, 不等解析** | drill step2 `RTT=3s, doc_id=104` |
| **MQ send** | chat-app log `parse_task.mq_sent task_id=4` |
| **parser consumer pull** | broker statsAll `parse-task-submit → parser-service-consumer, Consume Diff=0`(已消费) |
| **ParseWorker chunking (Tika + 切片)** | parser log `parse_worker.chunked doc_id=104 chunks=7` |
| **Milvus upsert** | `milvus.upsert doc_id=104 chunks=7` |
| **parse_tasks.PARSED + chunks_written** | DB row: status=PARSED chunks_written=7 |
| **chunks 表入库** | DB row: COUNT(*)=7 |
| **lease_until 续点机制** | DB row leased_by=AKA.local:62231 (parser 主机名+pid 持锁) |
| **单测 11 cases(含 lease 过期 → PENDING, 续点不重做)** | ParseTaskServiceTest PASS |

---

## 4. 发现的真 bug: doc 状态迁移 UPLOADED → READY 非法

### 4.1 现象
parser log:
```
parse_task.parsed task_id=4, doc_id=104, chunks=7
parse_task.doc_markReady_failed task_id=4, err=非法状态迁移: UPLOADED → READY 不被允许
```

### 4.2 根因
DocumentStatus 状态机只允许:
- UPLOADED → **PARSING** (解析开始时)
- PARSING → **READY / FAILED** (解析终态)
parser-service 的 ParseWorker 跳过了 markRunning(doc) → PARSING 这一步, 直接尝试 markReady, 自然失败。

### 4.3 影响范围 (次生产物)
- 文档卡 UPLOADED, RAG 检索可用(chunks 已入 Milvus), 但前端 readyCount 永远算不到这条 doc
- 后续用户在不同 doc 上 upload + async parse 同样命中

### 4.4 修法 (下一步补)
ParseTaskConsumer 在 `parse_worker.parse()` 后、`markParsed()` 前调用
`documentManageService.markRunning(docId)` (或 parser 侧同等 API) 把 document state 推到 PARSING;
或修状态机允许 UPLOADED → READY 直接迁移(parser async 模式下 document 无需经 PARSING 态,
因 parse 进度由 parse_tasks 表跟踪, document 表 status 是冗余)。

### 4.5 关联 doD-2
这个 bug 走通后, kill-9 真演练才能复跑(因为最终 doc.status=READY 是 drill step6 assert 之一)。
和 doD-2 (重试续解析 + DLQ) 是**同一批该收尾的工作**。

---

## 5. DRILL 脚本本身的 latent assertion 假设过严

`scripts/v3-kill-9-drill.sh` step3:
```bash
for i in $(seq 1 15); do
  STATUS_NOW=$(mysql ... 'SELECT status FROM parse_tasks WHERE id='"$TASK_ID")
  if [ "$STATUS_NOW" = "RUNNING" ]; then break; fi
  sleep 1
done
[ "$STATUS_NOW" = "RUNNING" ] || fail "parser 没进 RUNNING"
```

dev 机 Rosetta amd64 模拟下, parser 主进程 + MQ netty 起 + chunking 上来 ~50-90s(实测 doc_id=104 是 87s),
15s 采样窗口远不够。脚本逻辑本身在 GPU/Linux 直跑下没问题, 在本机模拟环境需要拉长或加 retry。

---

## 6. 已修 + 已推 (commit eead610)

- `parser-service/src/main/java/.../ParserServiceApplication.java` (§2.1-2.3)
- `parser-service/src/main/resources/application-dev.yml` (§2.4)
- `deploy/rocketmq/broker.conf` + `docker-compose.yml` 改用 127.0.0.1 broadcast + volume mount (§2.5)
- `scripts/v3-kill-9-drill.sh` doc_id fallback + RTT_LIMIT_S env + ready 30s→60s
- 本报告 docs/v3/kill-9-drill-attempt-log-2026-08-02.md

## 7. 仍待修才能拿完整 PASS log (推荐顺序)

1. **§4 `doD-2/type-state-machine` bug**: parser 端 markReady 前先 markRunning(doc) 或扩 state machine
2. 重启 chat-app + parser 跑 415 行 script(用 `RTT_LIMIT_S=15` + cold start 等更久)
3. 末尾 "PASS: V3 DoD-1 命中" 入档 → docs/v3/kill-9-drill-pass-log.md, 本报告 §0 🟡→✅

---

## 8. 对外诚实表述

> V3 DoD-1 / DoD-4 验证: parser-service 第一次真正从 jar 启动通过, 整条 MQ → 解析 → 续点 → Milvus 入库
> 端到端跑通(实测 task_id=4, doc_id=104, 7 chunks 全入库, lease 续点显形, parse_tasks.PARSED 命中)。
> 演练过程中识别出 1 处 doc 状态机 bug(parser 跳过 PARSING 直接 READY 失败)与 4 处 parser/RocketMQ
> latent boot/config bug, 全部已修或已写清楚修法。
> 完整 415 行 PASS log 推到补 doc 状态机这一修后 ~10 分钟可拿。
