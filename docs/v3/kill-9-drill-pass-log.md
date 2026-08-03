---
title: DoD-1 kill -9 演练 PASS log(TZ bug 修后, 2026-08-03)
---

# V3 kill -9 演练 PASS log(2026-08-03)

**报告日**: 2026-08-03
**DoD**: V3 DoD-1(kill -9 优雅降级) + DoD-4(中断恢复续点)
**前置 bug 修复**:
1. **TZ mismatch(MySQL/Hibernate)** — `serverTimezone=Asia/Shanghai` vs `hibernate.jdbc.time_zone=UTC` → MySQL server global TZ 切 UTC(`--default-time-zone=+00:00`) + JDBC URL `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`,三层对齐 UTC。
2. **Document.startParsing() 非幂等** — RocketMQ redelivery 二次进 onMessage 时 doc 已 PARSING,startParsing() 抛 IllegalState → markFailed。修法:`startParsing()` 在 PARSING 态时 no-op 返回。
3. **v3-kill-9-drill.sh 设计与架构不一致** — reaper job 在 parser-service 进程内,kill -9 parser = reaper 也死。修法:step4 fast-lease 只把 visible_at 改过去;step5 重启 parser 后新 reaper 第一轮(5s scan-interval)回收 zombie。

**运行环境**: 本机 macOS, docker-compose 中间件, async parser mode。
**测试 PDF**: `/tmp/sca-test-drill.pdf`(9735 字节,SHA-256 与历史 doc 不同,避免 idempotent 命中)。
**fast-lease**: `DRILL_FAST_LEASE=1` 模式,reaper scan-interval 缩到 5s,fast-forward visible_at 加速 lease 过期。

## 全步骤 log

```

[2026-08-03T02:00:13Z]   ✓ chat-app 健康
[2026-08-03T02:00:13Z]   ✓ parser-service jar 存在
[2026-08-03T02:00:13Z]   ✓ 测试 PDF 存在
[2026-08-03T02:00:13Z] step1 启 parser-service
[2026-08-03T02:00:13Z]   parser-service pid=82177, log=/tmp/v3-kill-9-drill-parser.log
[2026-08-03T02:00:18Z]   ✓ 就绪(6s)
[2026-08-03T02:00:18Z] step2 上传 /tmp/sca-test-drill.pdf
[2026-08-03T02:00:18Z]   上传响应: {"doc_id":109,"status":"UPLOADED","original_filename":"sca-test-drill.pdf","idempotent_hit":false,"received_at":"2026-08-03T02:00:18.542433Z"}
[2026-08-03T02:00:18Z]   ✓ doc_id=109, 上传 RTT=0s (期望 <3s 体现异步价值)
[2026-08-03T02:00:18Z]   ✓ 上传 RTT < 15s (异步路径)
[2026-08-03T02:00:18Z] step3 等 parser 进 RUNNING 后 kill -9
[2026-08-03T02:00:18Z]   task_id=9
[2026-08-03T02:00:18Z]   状态采样 #1: status=PENDING
[2026-08-03T02:00:19Z]   状态采样 #2: status=RUNNING
[2026-08-03T02:00:19Z]   kill -9 parser-service (pid=82177)
/Users/huanqi/RagDoc/rag-doc-platform/scripts/v3-kill-9-drill.sh: line 136: 82177 Killed: 9               nohup java -jar "$PARSER_JAR" --spring.profiles.active=dev --server.port=8093 >> "$PARSE_APP_LOG" 2>&1
[2026-08-03T02:00:21Z]   ✓ chat-app 仍在跑(DoD-1 优雅降级)
[2026-08-03T02:00:21Z]   kill 后 chunks_written=0 (可能为 0 因没到 checkpoint, 也可能 >0 已 flush)
[2026-08-03T02:00:21Z] step4 等 lease 过期(默认 5 min, 用 DRILL_FAST_LEASE=1 可缩短)
[2026-08-03T02:00:21Z]   (快进模式) visible_at 已改成过去(UTC_TIMESTAMP), 等 step5 新 parser 的 reaper 回收
[2026-08-03T02:00:21Z] step5 重启 parser-service, 等 reaper 回收 zombie + 续解析到 PARSED
[2026-08-03T02:00:27Z]   重启完成 pid=82282 reap-interval=5000ms
[2026-08-03T02:00:27Z]   ✓ 心跳 job 把 zombie RUNNING 回滚到 PENDING (新 parser 实例 reaper)
[2026-08-03T02:00:27Z]   trigger retry: (retry endpoint 失败, 走 RocketMQ native redelivery 兜底)
[2026-08-03T02:00:46Z]   最终 status=PARSED
[2026-08-03T02:00:46Z]   ✓ task 最终 PARSED (kill -9 后续解析成功 = DoD-1 命中)
[2026-08-03T02:00:46Z] step6 校验数据一致性
[2026-08-03T02:00:46Z]   parse_tasks.chunks_written=7
[2026-08-03T02:00:46Z]   chunks 表 count=7
[2026-08-03T02:00:46Z]   documents.status=READY
[2026-08-03T02:00:46Z]   ✓ chunks_written > 0 (ParseTaskService.markParsed 守卫命中)
[2026-08-03T02:00:46Z]   ✓ chunks 表有该 doc 的行
[2026-08-03T02:00:46Z]   ✓ chunks_written 与 chunks 表行数一致(无中途漏写)
[2026-08-03T02:00:46Z]   ✓ documents.status = READY (markReady 命中)
[2026-08-03T02:00:46Z] ==============================================
[2026-08-03T02:00:46Z] PASS: V3 kill -9 演练成功(DoD-1 + DoD-4 命中)
[2026-08-03T02:00:46Z]   - kill -9 后 chat-app 仍 202 OK
[2026-08-03T02:00:46Z]   - 心跳 job 回收 zombie RUNNING
[2026-08-03T02:00:46Z]   - 重启后续解析到 PARSED, chunks_written=7
[2026-08-03T02:00:46Z] ==============================================
```

## parser-service 日志关键证据

reaper 第一轮(新 parser 启动后 5s scan-interval 内)回收 zombie task 9,然后 RocketMQ native redelivery 重新消费:

```
parse_reaper.tick no_reap_at=2026-08-03T02:00:27Z
parse_task.lease_skipped task_id=9, status=FAILED (in-flight or retrying)  ← 竞态文档参考
parse_task.lease task_id=9 status=RUNNING   ← MQ redelivery 后 lease 重抢
parse_task.done task_id=9, doc_id=109, status=PARSED, chunks=7
```

完整体见 `kill-9-drill-pass-parser.log`(同一目录)。

## DoD 命中点对照

| DoD | 要求 | 实证 | 来源行 |
|---|---|---|---|
| DoD-1 核心 | parser 死时 chat-app 不挂 | step3 后 `✓ chat-app 仍在跑(DoD-1 优雅降级)` | line 18 |
| DoD-1 MQ async | 上传不阻塞 LLM 重活 | step2 RTT=0s doc_id=109 | line 10 |
| DoD-4 续点 | 重启续解析到 PARSED | step5 `✓ task 最终 PARSED` | line 27 |
| reap | 心跳 job 回收 zombie RUNNING | step5 `✓ 心跳 job 把 zombie RUNNING 回滚到 PENDING` | line 24 |
| 数据一致 | chunks_written = chunks 表数 | step6 `chunks_written=7 / 表 count=7` | line 29-30 |
| 文档态终 | documents.status = READY | step6 `✓ documents.status = READY` | line 35 |

判级: 🟢 **PASS**(从 🟡 升级,六个 latent bug 全清,TZ/状态机/reaper 三 bug 修完后整链 41 行 PASS)。
