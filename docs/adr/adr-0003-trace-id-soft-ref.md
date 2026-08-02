# ADR-0003: trace_id 软引用 — feedbacks 不加外键到 chat_traces

- Status: Accepted
- Date: 2026-07-28

## Context

V1 引入 `chat_traces` 表(V2 Flyway 迁移)和 `feedbacks` 表(V1 已存在)。
两者通过 `trace_id` 关联:用户提交反馈必须能追溯到一次真实的 chat 调用。

两种实现方式可选:
- **硬外键**: `feedbacks.trace_id VARCHAR(64) REFERENCES chat_traces(trace_id)`
- **软引用**: 仅靠应用层 `FeedbackService` 查表校验

约束:
1. chat_traces 由 ChatService 写入,与 chat 响应同事务。
2. feedbacks 允许 V1 单租户, V4 多租户;未来跨库可能性不能排除。
3. 团队追求"按功能点可独立测试", 不希望 feedback 强绑定 chat 表结构。

## Decision

**采用软引用, 不加外键约束。**

- DDL 不加 `FOREIGN KEY` (chat_traces V2 迁移与 feedbacks V1 都未引对方)
- `FeedbackService.submit()` 写入前必须执行:
  ```sql
  SELECT 1 FROM chat_traces WHERE trace_id = ? LIMIT 1
  ```
  查不到即抛 `NotFoundException(ErrorCode.TRACE_NOT_FOUND)`
- chat_traces 写入与 ChatService 业务逻辑在同一 `@Transactional` 内, 保证 commit 后 feedback 才能查到

## Alternatives Considered

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 硬外键 | DB 强一致, 不允许孤儿 | 跨库迁移痛, 删 chat_traces 必须级联或限流 | 否决 |
| **软引用** | 解耦, 应用层校验可控 | 理论可能写入孤儿(应用 Bug 时) | **采纳** |
| 不校验 | 最简单 | feedback 可被伪造任意 trace_id | 否决 |

## Consequences

**正面**:
+ feedback 表结构独立, 跨库 / 拆服务(V3 微服务化)零迁移成本
+ 删 chat_traces 历史数据不会阻塞 feedback(老 feedback 仍可读,只是 trace 不可查)
+ 单测 feedback 只需 mock chat_traces 仓库, 不需真起 chat 流程

**负面**:
- 应用 Bug 可能导致孤儿 feedback (trace_id 在 chat_traces 已不存在却留了 feedback)
- 理论并发 race: chat 响应未 commit, feedback 立即查 → TRACE_NOT_FOUND 误报

**缓解**:
- chat_traces 写入在 ChatService 同事务内, 与返回响应为原子动作;正常客户端拿到 trace_id 时表已有记录
- 单测 + 集成测试覆盖 TRACE_NOT_FOUND 场景, 防回归
- V3 起补定时任务巡检孤儿 feedback (低优先级)

## Revisit

- 若未来发现孤儿 feedback 数据剧增 → 评估上硬外键
- 若 chat_traces 表迁移到独立服务 → 强制软引用, 不可回退硬外键
