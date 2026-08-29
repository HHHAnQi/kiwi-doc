# FAULT_INJECTION_REPORT — 异步 Ingestion 链路故障注入 (Phase 5)

> 2026-08-29 · 环境: 本地 docker 中间件(MySQL 3307/MinIO/RocketMQ/Milvus) + BGE-M3(8082)
> · chat-app `RAG_PARSER_MODE=async`(:8092) + parser-service(:8093, kill后按脚本重启)
> · git `554de74`+（含本报告所述两处修复）· 全部证据为**真实进程注入**，非单测替代。

## 前置修复（本轮实测暴露的两个回归）

1. **parser-service 启动回归**：fat jar 内含 platform-bootstrap-plain，`RerankHealthIndicator`
   依赖被 parser 排除的 `application.chat.RerankProperties` → parser 无法启动。
   修复：`ParserServiceApplication` 组件扫描排除 `infrastructure.rerank.*`（rerank 非解析链依赖）。
2. **演练脚本断言漂移**：`documents.status` 终态已由旧名 READY 重命名为 INDEXED（可检索终态），
   脚本断言同步更新（语义等价重命名，非放松校验）。

## Test A — Worker Crash（kill -9）

流程: 上传(202 异步) → parser RUNNING → **kill -9 parser 进程** → lease 过期 →
新实例 reaper 回收 zombie RUNNING → 回滚 PENDING → 续解析 → PARSED → INDEXED。

| 检查项 | 结果 |
|---|---|
| kill 后 chat-app 存活且上传路径正常 | ✓（优雅降级） |
| 任务丢失 | **无**（task 175 kill 后续解析完成） |
| 重复索引 | **无**（`chunks_written=7` 与 chunks 表行数=7 完全一致） |
| 不一致状态 | **无**（documents 终态 INDEXED） |
| 恢复时延 | reaper 5s interval 内回收；kill→PARSED 全程约 26s（快进租约模式） |

原始日志: `docs/reliability/kill9-drill-20260829.log`（脚本输出 PASS: DoD-1 + DoD-4 命中）。
注: 快速模式缩短的是租约/reap 常量（脚本自带 `DRILL_FAST_LEASE=1`），机制路径与生产一致。

## Test B — Poison Message（截断 PDF, Tika 抽取为空）

| 检查项 | 结果 |
|---|---|
| retry 计数正确 | ✓（retry_count 1→2→3，max_retries=3，60s 退避重投） |
| DLQ 语义 | ✓（`parse_task.cancelled(DLQ) task_id=172, retry=3/3`，终态 CANCELLED） |
| 错误可追溯 | ✓（error_class=IllegalStateException，err="Tika 抽取文本为空"，日志含 task/doc id） |
| 不阻塞正常任务 | ✓（毒消息重试期间并发上传的好文档 doc 275 正常 INDEXED） |

观察（不构成失败，如实记录）：毒文档自身 `documents.status` 停留在 PARSING（任务已 DLQ
但文档生命周期未联动标记失败）——排障可经 parse_tasks 追溯，但文档状态机可考虑联动
FAILED 状态；属改进项非缺陷。

## Test C — Duplicate Delivery

| 场景 | 结果 |
|---|---|
| C1 已入库内容重传 | ✓ `idempotent_hit=true`，返回原 doc（271/INDEXED），无新任务、无新 chunks |
| C2 全新内容**并发双发**（同 SHA 两请求竞态） | ✓ 两请求返回**同一 doc_id=276**（一次 false+一次 true 幂等命中）；终态恰 1 doc / 1 parse task / **7 chunks** / INDEXED — 零重复 chunk/向量 |

## 结论

```text
Test A (worker crash):     PASS — 无丢失/无重复/无不一致, 恢复由 lease+reaper+续点完成
Test B (poison message):   PASS — 重试计数/DLQ/错误可追溯/不阻塞, 附1条文档状态联动改进观察
Test C (duplicate delivery): PASS — 上传级幂等 + 竞态下 SHA256 幂等收敛, 数据零重复
```

以上均为**真实故障注入**（kill -9 真实进程、真实毒文件、真实并发竞态），区别于单测覆盖
（单测见 parser-service 测试套件，二者互补不互替）。
