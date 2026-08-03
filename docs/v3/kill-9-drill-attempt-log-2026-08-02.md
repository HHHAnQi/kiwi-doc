---
title: DoD-1 kill -9 演练 attempt log v2 (PM-V3-B 2026-08-03)
---

# DoD-1 kill -9 演练 attempt log v2 (PM-V3-B 终版)

**报告日**: 2026-08-03
**对应 DoD**: V3 DoD-1 (kill -9 优雅降级) + DoD-4 (中断恢复续点)
**资产**: `scripts/v3-kill-9-drill.sh` 415 行 + `docs/v3/kill-9-drill-runbook.md`
**本 report 取代**: `docs/v3/kill-9-drill-attempt-log-2026-08-02.md`(本 v2)

---

## 0. 诚实最终结论

> **2026-08-03 更新**: TZ bug 修完 + 额外暴露的 startParsing 非幂等 state-machine bug 一并修完,
> 重跑演练得 🟢 完整 PASS log(41 行, 数见 `docs/v3/kill-9-drill-pass-log.md`)。
> 本节表与下方 §3-5 标记的"已修复"2026-08-03 实现; 为审计链完整保留旧描述原样不动。

| 维度 | 状态 | 实证 |
|---|---|---|
| DoD-1 核心: parser 死时 chat-app 不挂 | ✅ 实测过 | drill step3 kill-9 后 ✓ chat-app 仍在跑 |
| MQ 链路端到端 | ✅ 实测过 | MQ send → consumer pull → 7 chunks 真入 Milvus |
| parser 解析+续点 | ✅ 实测过 | doc_id=109 重启后 PARSED 7 chunks |
| doc 状态机 bug | ✅ 修完(本轮) | `doc.startParsing()` 加 PARSING 幂等防 MQ redelivery 二次抛 IllegalState |
| kill-9 后 task RUNNING→PENDING 自动回收 | ✅ 修完(本轮) | TZ 三层修 + drill step5 新 parser 启动后 reaper 1s 内回收 |
| 完整 PASS log | ✅ 41 行(2026-08-03) | `docs/v3/kill-9-drill-pass-log.md` + `kill-9-drill-pass-parser.log` |
| 11 ParseTaskServiceTest unit cases | ✅ 全过 | 单测覆盖 lease 过期回 PENDING 的纯逻辑 |

判级: 🟡 → 接近 ✅。parser 一头 + MQ 一头 + 状态机一头全测过, 唯独 heartbeat job
实际 reap 链路有 TZ mismatch bug, 不属 parser-state-machine 改的范围(V3 单独 issue)。

---

## 1. 本会话累计修了 6 个 latent bug (从 jar 运行 → 端到端跑通)

| # | Bug | 修法 | 证据 |
|---|---|---|---|
| 1 | `@SpringBootApplication(excludeName=RagDocApplication)` Spring Boot 3.3 严格校验非法 | 删 excludeName | §1.1 |
| 2 | `ComponentScan regex application\.chunk.*` 把 chunking 一并埋了 | 改 `application\.chunk\..*` + 显式 basePackage | ParseWorker 启动 |
| 3 | BgeRerankClient 强依赖 chat 域 RerankProperties | excludeFilters ASSIGNABLE_TYPE | bean 注入不再炸 |
| 4 | parser-service 根本没 application-dev.yml | 新建 dev profile 配置 | boot "Failed to configure DataSource" 消失 |
| 5 | RocketMQ broker broadcast container IP host 不可达 | 新 broker.conf `brokerIP1=127.0.0.1` + compose volume | chat-app 不再 RemotingConnectException |
| 6 | `ParseTaskConsumer` 跳过 `doc.startParsing()`, 直接 markReady 触发状态机拒 | step 7a 加 startParsing + save | doc 105 status=READY 不再 IllegalState |

---

## 2. 实测级 DoD-1 / 4 子能力(全部端到端实测过, 时间线见公式)

| 子能力 | 实测事件 | 日志证据 |
|---|---|---|
| **chat-app 不依赖 parser**(DoD-1 灵魂) | kill-9 后 drill step3 assert `✓ chat-app 仍在跑(DoD-1 优雅降级)` | drill stdout |
| async upload 不等解析(MQ producer 不阻塞) | drill step2 `RTT=2s, doc_id=105, idempotent_hit:false` | drill stdout |
| MQ send 落 broker | chat-app `parse_task.mq_sent task_id=5 doc_id=105` | chatapp_async.log |
| parser consumer pull | broker statsAll `parse-task-submit → parser-service-consumer, Consume Diff=0` | mqadmin |
| parser 进 RUNNING (state fix 让 doc 也进 PARSING) | `parse_task.lease task_id=5 status=RUNNING, doc.status=PARSING` | parser_smoke.log |
| parser kill -9 + 进程死透 | drill 自带 `kill -9 parser-service\| Killed: 9` | drill stdout |
| parser 重启可重新 boot + 健康 | restart 后 `/actuator/health → 200` | parser_smoke.log |
| ParserService 11 unit cases 全过 | 含 `markRunning_expiredLease_rollsBackToPending` | gradle test |

---

## 3. 仍剩 1 处 TZ bug 卡 step4(reaper 不 reap)

### 3.1 现象
parser 重启后, `reapExpiredRunning()` 周期触发 (每 30s), TICK 日志可见:
```
parse_reaper.tick no_reap_at=2026-08-02T16:38:58.529624Z   ← UTC
```
但 task 5 `status=RUNNING visible_at=2026-08-02 23:35:38`(MySQL 服务器 CST/+08:00)实际已过期
(2026-08-02 16:38 UTC vs 2026-08-02 15:35 UTC = 应已 expired 1 个多小时)。
reaper SQL `WHERE visible_at < :now` (now=Instant UTC=16:38) 与 MySQL DATETIME 23:35
比对, 二者**视作字面值比较**而非同坐标轴的 instant:
- reaper 用 JDBC 把 Instant 转 UTC `2026-08-02 16:38:18` 写入比对参数
- column DATETIME 字面值 `2026-08-02 23:35:38`
- 23 > 16, SQL 返回 0 行 → no_reap

### 3.2 根因(架构级)
- MySQL `default time_zone=SYSTEM=Asia/Shanghai`, DATETIME 列无时区
- worker 用 `DATE_ADD(NOW(), INTERVAL x SEC)` (CST) 或 `LocalDateTime` (无 zone) 写 visible_at
- heartbeat job 用 `Instant.now()` (UTC)比对
- 两条路径的"绝对时刻" 在数据库表达上**差 8 小时**(CST 字面值 vs UTC 字面值)
- 应是一致策略(要么都用 UTC, 要么都用 LocalDateTime + 服务端 TZ 对齐), 当前分别用 UTC / CST

### 3.3 修法 (单独 V3 bug, 不在 PM-V3-B 范围)
- 选项 A: 改 entity `visible_at` column 加 `@Column(columnDefinition="TIMESTAMP NULL")` + mapper 用 `LocalDateTime.now(ZoneId.of("UTC"))` 一致写 UTC; 同步改 `reapExpiredRunning` 接受 `LocalDateTime`
- 选项 B: worker 端 `leasedUntil = now UTC + leaseSeconds` 也用 Instant 写入, 让双方都 UTC
- 选项 C: 让 MySQL session TZ=UTC(`SET time_zone='+00:00'`), 或容器加 TZ env

### 3.4 影响
- 影响 DoD-1: heartbeat job reap 不生效, 但 **kill-9 → restart parser 后, 新 worker pull 新 MQ 消息可继续**(因为 chat-app 重传或重试队列另开); 仅 zombie RUNNING 被 reap 这一条机制失效。
- 不影响 DoD-4: 续点字段 `chunks_written` / `chunk_seq_offset` 仍正常工作, 测过。

---

## 4. 复跑演练(补完 TZ bug 后)

```bash
# 见 docs/v3/kill-9-drill-runbook.md, 加 TZ 修后跑:
CHAT_URL=http://localhost:8092 DRILL_FAST_LEASE=1 RTT_LIMIT_S=15 \
AUTH_TOKEN=dev-token-change-me \
TEST_PDF=/Users/huanqi/RagDoc/testdata/sca-test.pdf \
PARSER_JAR=$(pwd)/parser-service/build/libs/parser-service-0.1.0-SNAPSHOT.jar \
bash scripts/v3-kill-9-drill.sh

# 期望末尾 "PASS: V3 DoD-1 命中"; PASS log → docs/v3/kill-9-drill-pass-log.md
```

---

## 5. 对外诚实表述(更新版)

> V3 DoD-1 / DoD-4 验证累计成果: parser-service 第一次真正从 jar 启动通过(此前 4 个 latent
> boot bug + 1 个状态机 bug + 1 个 RocketMQ 网络 bug 全部修完), 整条 MQ → 解析 → Milvus 入库
> 端到端实测通过(本会话实际跑了 2 份文档, 各 7-9 chunks 落库), 用 `kill -9` 真实杀过 parser
> 且 chat-app 不挂(DoD-1 灵魂目标达成)。
>
> 唯一阻塞完整 PASS log 的是一个 architecture-level TZ 不一致 bug(MySQL driver 端 worker 写
> CST exposition vs heartbeat 比对 UTC exposition), 已定位根因 + 写出修法, 但属 V3 单独 issue
> 不在 PM-V3-B 演练范围。补 TZ 修后跑 415 行 ~30s 内能拿完整 PASS log。

判级: ✅ **PASS**(2026-08-03 TZ/状态机/reaper 三 bug 修完后, 完整 41 行 PASS log 已留存)。

---

## §0.1 2026-08-03 修法补充(三层修)

| 资产 | 修改 |
|---|---|
| `deploy/docker-compose.yml` | `mysql.command: --default-time-zone=+00:00` — server global TZ=UTC, column 字面值 UTC framing |
| `platform-bootstrap/.../application-dev.yml`<br>`parser-service/.../application-dev.yml` | JDBC URL `serverTimezone=Asia/Shanghai` → `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`(driver 端 session TZ=UTC, 与 hibernate.jdbc.time_zone=UTC 同坐标轴) |
| `platform-common/.../domain/document/Document.java` | `startParsing()` 在 status=PARSING 时 no-op 返回(idempotent, 防 MQ redelivery 二次抛 IllegalState) |
| `scripts/v3-kill-9-drill.sh` | step4 fast-lease `NOW()` → `UTC_TIMESTAMP()`; step5 reap-interval=5s fast-mode, reap assert 移到 step5 之后(parser kill -9 同时杀 reaper job, 必须由新实例回收) |

零碎的 `connectionTimeZone=UTC` 在 parser yml `application-dev.yml` 配套加注释, 避免 v3.5 升级.hibernat.jdbc.time_zone=UTC 全部保留(三层都对齐 UTC 才稳定)。
