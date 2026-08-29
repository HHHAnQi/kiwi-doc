# P2-D5 Failure Reconstruction Completeness Audit — 2026-08-27

> D1-D4 结论冻结。本轮只审计可重建性, 无生产代码修改。实证来源: 代码 + Post-D3
> pilot 真实数据(run API 查询验证)。

## 1. Authoritative Event Timeline

| EVENT | DB | Trace/Log | METRIC | RESPONSE | RECONSTRUCTABLE |
|---|---|---|---|---|---|
| request accepted | ✗ | ✓ trace_id(MDC) | ✗ | ✓ traceId/X-Trace-Id | YES |
| planner attempt | △ run创建后routerVersion=model-llm-v1[:retry]/rule-fallback-v1:REASON | ✓ attempt_failed/retry_success/rule_fallback(带run或req+trace) | ✓ planner_degradation_total{stage} | ✗ | PARTIAL(成功路径YES) |
| run created | ✓ agent_run | ✓ transition | ✗ | △ 仅成功时有X-Agent-Run-Id | YES |
| phase-0 / tool call | ✓ agent_step(status/latencyMs/errorCode/replayed/deduplicated) | ✓ + Langfuse(call_id/run_id) | ✓ tool聚合 | ✗ | YES |
| **sufficiency decision** | **✗ 不落库** | △ 仅model_fallback转换日志 | ✓ 聚合sufficiency_total{outcome} | ✗ | **NO(逐run)** |
| guard decision | ✗ | ✗ 无日志 | ✗ | ✗ | **NO** |
| replan decision | △ ALLOWED→replan-*步落库; DENIED→拒绝原因进terminalReasonCode | △ | ✓ 聚合replan_total | ✗ | PARTIAL |
| phase-1 | ✓ replan-* steps | ✓ | ✓ | ✗ | YES |
| compose | ✗ | ✓ 日志 | ✓ llm_calls{composer} | ✓ answer | PARTIAL |
| terminal state | ✓ status+terminalReasonCode | ✓ | ✗ | △ 失败路径无runId | YES |

## 2. 六场景重建性(实证)

| 场景 | 只给 traceId/requestId 能否回答"为什么" | 依据 |
|---|---|---|
| A. Model失败→retry→Rule兜底 | **YES**(run创建后) | run API planner_version=model-llm-v1:retry / rule-fallback-v1:REASON(D3后落库) |
| B. Model+Rule失败→Classic兜底 | **NO** | 无DB记录/无run可查; 仅日志(req+trace+reason)+聚合metric → PRE_RUN_FALLBACK_AUDIT_GAP=TRUE |
| C. Phase-0工具失败 | **YES** | step.errorCode + terminalReasonCode=TOOL_FAILED(run API) |
| D. INSUFFICIENT→Replan→成功 | **PARTIAL** | replan-*步可见; 但最终reason被Pipeline覆盖为PLANNED_ANSWER_READY(实证: smoke run含3条replan步, terminal仅PLANNED_ANSWER_READY) |
| E. CONFLICTED→REFUSED_CONFLICT | **YES(状态级)** | 实证 run 19dfe143: REFUSED_CONFLICT/CONFLICT+steps; 但**冲突细节(哪些事实/证据)不落库** |
| F. Replan耗尽→有界降级 | **PARTIAL** | 与D同样被覆盖——**无法区分"补齐后成功"与"降级回答"** |

## 3. 特别审计结论

```text
PRE_RUN_FALLBACK_AUDIT_GAP=TRUE
  (Classic兜底发生在agent_run创建前: DB无记录, run API不可查,
   降级原因仅存在于结构化日志[req+trace+reason]与聚合metric; 
   traceId可在日志内串起Agent attempt与Classic response, 但依赖日志保留期)

PER_RUN_DECISION_GAP=TRUE
  (SUFFICIENT/INSUFFICIENT/CONFLICTED与guard结论不逐run持久化;
   最终reason被PLANNED_ANSWER_READY覆盖 → D/F不可区分;
   Prometheus只回答"发生了多少次", 不能回答"这个用户为什么失败")

Trace correlation 缺口:
  ① 失败路径响应不携带runId(MDC仅在prepared.ok()后设置: Pipeline:109/212),
    DB行存在但调用方无法从响应反查 → 拒答场景用户报障缺锚点;
  ② SSE与同步不一致: DoneEvent无runId(同步有X-Agent-Run-Id头);
  ③ metric label(stage/outcome/component)无法反查具体run(聚合设计使然);
  ④ planner_version在DB但不在HTTP响应。
```

## 4. Gate

```text
D5_STATUS=PARTIAL_OBSERVABILITY_GAP
```

### 值得修吗(correctness=零影响, 纯排障能力; 按最小菜单评估)

| 最小修复 | debug价值 | 成本 | 建议 |
|---|---|---|---|
| ① terminal decision summary(终态不覆盖: 保留PLANNED_REPLAN_SUFFICIENT/INSUFFICIENT_AFTER_REPLAN_FALLBACK, 或agent_run加decision_summary列含sufficiency outcome+replanCount) | 高(D/F区分、用户报障直达答案) | 低 | **FIX** |
| ② 失败路径响应暴露runId(prematureFailure也设MDC/响应字段) | 高(拒答场景可查) | 极低 | **FIX** |
| ③ SSE DoneEvent补runId | 中(一致性) | 极低 | FIX(顺手) |
| ④ pre-run Classic兜底: 现有结构化日志已含req+trace+reason | 中 | 0(已存在) | EXPLAIN(依赖日志保留策略, 文档化即可) |

不引入 event store; 不做 request 级全事件溯源。三项 FIX 合计预估 < 60 行 + 测试。
