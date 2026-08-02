# V3 kill -9 演练 Runbook

**对应 ADR / spec**: ADR-0005 / ADR-0009 / docs/v3/parser-service-spec.md §4.2 / §7.3 / §9
**对应 DoD**: V3 DoD-1 (kill -9 优雅降级) + DoD-4 (中断恢复续点)

---

## 0. 目的

证明 parser-service 在生产中的两个故障韧性特征:

1. **进程死透仍能用** — parser 进程被 `kill -9` 后 chat-app 上传仍 202 OK, 业务不中断
2. **续点不重做** — 重启后从 `chunks_written` / `chunk_seq_offset` 续点, 已切的部分不重跑

跑过一次后, 本脚本是 V3-W1 验收的「演示硬资产」, 对应 spec §7.3 / §9 DoD-1 列。

---

## 1. 前置条件

| 项 | 期望状态 |
|---|---|
| docker-compose 中间件 | `make up` 已起: MySQL(3307) / MinIO(9000) / Milvus(19530) / **RocketMQ(9876)** |
| chat-app | port 8092, profile dev, env `RAG_PARSER_MODE=async` |
| BGE-M3 embedding | port 8082 健康(供 parser worker 调) |
| parser-service jar | `parser-service/build/libs/parser-service.jar` 已生成(否则 `./gradlew :parser-service:bootJar`) |
| MySQL client | 终端可用 `mysql` 命令 |

注: chat-app 上传端口话 — `application-dev.yml` 默认 chat-app 8080; spec §7.3 例子用 8092 是 ADR 实操选择;
跑演练时确认 chat-app `server.port` 与脚本 `CHAT_URL` 一致即可。

### RocketMQ 没 docker-compose 起?

V3 第 1 周 `deploy/docker-compose.yml` 暂未含 RocketMQ, 演练前手动加:

```yaml
  rmqnamesrv:
    image: apache/rocketmq:5.3.1
    container_name: rmqnamesrv
    ports: ["9876:9876"]
    command: sh mqnamesrv
  rmqbroker:
    image: apache/rocketmq:5.3.1
    container_name: rmqbroker
    ports: ["10909:10909", "10911:10911"]
    environment:
      NAMESRV_ADDR: "rmqnamesrv:9876"
    command: sh mqbroker -c /home/rocketmq/rocketmq-5.3.1/conf/broker.conf
    depends_on: [rmqnamesrv]
```

(待 V3-W1 末 commit, 本段会合到主 docker-compose.yml)

---

## 2. 启 chat-app (async mode)

```bash
# 在 repo 根
export RAG_PARSER_MODE=async
export TEST_AUTH_TOKEN=dev-token-change-me
./gradlew :platform-bootstrap:bootRun --args='--spring.profiles.active=dev --server.port=8092'

# 另一终端验证
curl -H "Authorization: Bearer dev-token-change-me" \
     http://localhost:8092/actuator/health
```

启动日志应看到 `ParseTaskProducer` bean 装配, **没有** `TikaParsingTrigger` bean (async 模式排除)。

---

## 3. 打 parser-service jar 并启

```bash
./gradlew :parser-service:bootJar
java -jar parser-service/build/libs/parser-service.jar \
  --spring.profiles.active=dev \
  --server.port=8093
```

启动日志应看到:
- `RocketMQMessageListener started` (consumer 注册)
- 心跳 job `@Scheduled` 已 schedule (`@EnableScheduling` 生效)
- 与 chat-app 共用 MySQL + Milvus + MinIO 实例

---

## 4. 跑演练脚本

```bash
# 全流程, 含等 lease 过期 5.5min(默认)
./scripts/v3-kill-9-drill.sh

# OR 快进模式: 直接改 visible_at 为过去跳过 5min 等待(用于 CI)
DRILL_FAST_LEASE=1 ./scripts/v3-kill-9-drill.sh
```

---

## 5. 期望输出 / 验收点

脚本末尾:

```
[ts] step3 等 parser 进 RUNNING 后 kill -9
[ts]   task_id=1
[ts]   状态采样 #1: status=PENDING
[ts]   状态采样 #2: status=RUNNING
[ts]   kill -9 parser-service (pid=12345)
[ts]   ✓ chat-app 仍在跑(DoD-1 优雅降级)
[ts]   kill 后 chunks_written=10
[ts] step4 等 lease 过期(默认 5 min, 用 DRILL_FAST_LEASE=1 可缩短)
...
[ts] step5 重启 parser-service 续点
[ts]   最终 status=PARSED
[ts]   ✓ task 最终 PARSED (kill -9 后续解析成功 = DoD-1 命中)
[ts] step6 校验数据一致性
[ts]   parse_tasks.chunks_written=47
[ts]   chunks 表 count=47
[ts]   documents.status=READY
[ts]   ✓ chunks_written > 0
[ts]   ✓ chunks 表有该 doc 的行
[ts]   ✓ chunks_written 与 chunks 表行数一致
[ts]   ✓ documents.status = READY
[ts] ==============================================
[ts] PASS: V3 kill -9 演练成功(DoD-1 + DoD-4 命中)
[ts]   - kill -9 后 chat-app 仍 202 OK
[ts]   - 心跳 job 回收 zombie RUNNING
[ts]   - 重启续解析到 PARSED, chunks_written=47
[ts] ==============================================
```

---

## 6. 失败模式与排查

| 故障现象 | 排查路径 |
|---|---|
| chat-app 仍走 sync(同步打 Tika) | env `RAG_PARSER_MODE` 没传给 chat-app 进程; 启动日志搜 "ParseTaskProducer" / "TikaParsingTrigger" 装的是哪个 |
| parser-service 启动失败, BeanCreationException | 多半是 RocketMQ broker 没起 → 检查 9876 端口 |
| parser 不抢 task(永远 PENDING) | dev profile `application.yml` rag.parser.mode 在 parser-service 侧没覆写 — parser 端默认 sync 会装载错 bean |
| kill -9 后心跳 job 不回收 | VisibilityTimeoutScheduler 没跑? `@EnableScheduling` 应在 ParserServiceApplication 上; 或 reap-interval-ms 配太长 |
| 续点时 chunks_written 从 0 开始重切 | ParseWorker checkpoint_progress 没调到? 看日志 `parse_worker.checkpoint task_id=...` 是否打过 |
| 状态卡 FAILED 不重试 | markFailed retry_count ≥ max_retries 已到 DLQ; 查 parse_tasks.attempts JSON 看每次 attempt 错因 |
| kill -9 后核心 dump 不进 PARSED | 看 parser log 找 Worker.execute 抛了什么, 多半 embed/Tika 异常 |

---

## 7. 后续(不在本演练范围)

- **DoD-2 重试续解析**: 需要 mock TikaParsingTrigger 抛异常连续 max_retries 次 → DLQ 落 RocketMQ `%DLQ%parse-task-consumer-group` topic。集成测试覆盖, 不在本脚本范围。
- **DoD-3 p95<2s**: Locust 100 并发压测, V3 第 4 周。
- **DoD-5 trace 接 Langfuse**: V3-W3。
- **DoD-6 灰度降级**: 切 `RAG_PARSER_MODE=sync` 验证降级路径, V3-W2 末补演练。
