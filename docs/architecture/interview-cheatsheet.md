# Agent 面试速查表（30 秒应答版）

> P0-3 修订版：所有数字对齐到有正式报告支撑的实验，并标明口径（Rule Planner 轮 vs LLM Planner 轮）。
> 出处：docs/evaluation/2026-08-25-agentic-paired-ab-final-report.md（Rule 轮，200题×3轮）、
> docs/evaluation/2026-08-27-p0-2-pilot-report.md（LLM 轮，50题，MODEL 47/50 有效）。

---

## 核心电梯稿（60 秒版）

> "我做了一个企业 RAG 平台，核心方法论是**评测驱动开发**。Classic 路径达到企业级水准（faith 0.971, Hit@5 92.5%）。Agentic 做了完整实现和两轮对照评测：第一轮 200 题×3 轮（规则 Planner）从 -45pp 修到 -24pp；我们发现评测对象配置漂移后又补了 50 题 LLM Planner pilot——LLM Planner 比规则版大幅改善（多跳 slice 0.624→0.830）但仍未反超 Classic（整体 -8.1pp，延迟 ×3.1），结论是**当前语料规模不需要启用 Agentic**。最有价值的两个发现：三方交叉验证揭示 LLM-as-Judge 格式偏见（judge 间一致 91% 但与人工仅 62-75%），以及**分解粒度与答案质量负相关**（dec≥2 时显著变差）——这是'什么时候不用 Agent'的实证支撑。"

---

## 10 个死亡问题 → 30 秒应答

### 1. "你的 Planner 和 if-else 有什么区别？"
> "分层的：Router Tier 处理 80% 简单题（零 LLM 调用、确定性路由），Planner Tier 处理多跳题（LLM 语义分解）。实测（50 题 pilot）LLM Planner 分解真实发生（多跳平均 2.3 步、单跳 1.08 步），且比规则版在多跳 slice 高 21pp（0.830 vs 0.624）——但仍低于 Classic 的整句检索（0.926）。所以现在 Planner 链的价值是**能力与降级兜底**（Model→retry→Rule→Classic 全链可观测），生产默认走 Classic。"

### 2. "Replan 为什么只有 1 次？"
> "50 题 LLM pilot 里 replan 0/47 触发——sufficiency 全部 Phase-0 放行，说明阈值偏松（已记为校准欠账）。上限 1 次是 bounded loop 约束：预算/签名去重/CAS 状态机保证终止；边距收益测过 <2pp（规则轮）。"

### 3. "Sufficiency 判定器被架空了？"
> "已修复三个问题：①传入原始查询（原来传空串）；②三档分离 SUFFICIENT/PARTIAL/INSUFFICIENT 可统计各自占比；③LLM Judge 异常从 fail-closed 改为 fail-open（判定器故障≠业务拒答）。遗留：校准偏松导致 pilot 零 replan，需要与人工标注对齐（R3）。"

### 4. "Token 预算为什么是 BigDecimal.ZERO？"
> "BudgetManager 的 denied 分支已实现，settle 路径已有 TokenEstimator 写入。配置层设 `RAG_AGENT_BUDGET_MAX_TOTAL_TOKENS=50000` 即激活（prod 默认已设）。dev 设 0 是因为不限制开发调试。已知限制：token 未逐 run 持久化，成本监控用步数和 LLM 调用数代理。"

### 5. "Checkpoint/lease 在防谁？"
> "lease 防跨实例重复执行（DB 条件 UPDATE 的 claim/heartbeat，真实实现）；checkpoint 每步落库。**如实说：resume 执行器未接线**——stale run 恢复作业目前的语义是检测 + 安全终止（SYSTEM_FAILED），不是续跑；checkpoint 的现值是故障诊断与已结算步骤审计。单机 dev lease 关闭零开销，多实例 prod 开启。"

### 6. "盲评 16 题能推翻 600 题 judge 结论？"
> "不能推翻——它揭示的是 direction（judge 可能有格式偏见）而非 proof（质量等同）。三方交叉（DeepSeek+Qwen+人工）16 题是方向性证据。评测 runner 同时修复了拒答子串判 0（改为精确匹配）和截断偏差（统一 1200 字符）。"

### 7. "你最大的修复是把 Classic 塞回 Agentic？"（最关键）
> "锚定不是修回 Classic，是**混合架构**：原查询保底 + 子查询增量（include_original 模式）。但 LLM pilot 的诚实结论是：全集 -8.1pp（95%CI 不含 0）、多跳 slice -9.6pp、延迟 ×3.1，且**分解步数越多答案越差**（1步 -2.7pp / 2步 -18pp / 3步 -11.8pp）——机制解释是子查询漂移 + 碎片证据稀释合成。Slice S 盲评换位 14:2 偏好 Agentic 表述，但绝对分仍低，属表述偏好非信息优势。所以生产配置 Agentic 关闭——这就是架构判断力。"

### 8. "Agentic 不支持多轮？"
> "已修复：PlannedAgentPipeline 注入 QueryContextualizer，execute() 入口做指代消解改写后传入 Planner。复用 Classic 已有组件。当前是会话级记忆（Redis TTL 24h + 压缩摘要 + 话题切换检测），跨会话长期记忆明确不做（业务无需求）。"

### 9. "Agent 上线后怎么监控？"
> "已接线：trace_id 贯穿日志（MDC）+ Langfuse Trace + `GET /agent/runs/{id}` 全步骤审计 API + planner 降级指标 `ragdoc.agent.planner_degradation_total{stage}`（Model 重试/Rule 兜底/Classic 兜底分计数）+ 降级来源写入 agent_run.plannerVersion。**如实说：另 5 个 agent 域指标（sufficiency 分布等）已注册但调用方未接线，是已知欠账。** MCP 入口有 RateLimiter（10 QPS）。"

### 10. "评测配置和生产配置不一样？"
> "设计意图：评测全开（需要评估 Agentic），生产全关（两轮评测结论都说当前不该开）。`application-prod.yml` 定义了生产标准（planner 全关 + token 预算 50k + lease 启用 + recovery=stale 检测安全终止）。Planner 降级链保证即使未来打开 model-enabled 也有 Rule→Classic 兜底。"

---

## 关键数字速记（全部有报告出处）

| 指标 | 数值 | 口径/出处 |
|---|---|---|
| Classic faithfulness | 0.971 | 基线报告 |
| Classic Hit@5 | 92.5% | 3 轮一致 |
| Agentic V1→V4（Rule Planner） | 0.263→0.497 | 08-25 报告，200题×3轮，差距 -24pp |
| LLM Planner（pilot）多跳 slice | 0.830 vs Classic 0.926 | 08-27 pilot，MODEL 47/50 |
| LLM Planner（pilot）全集 | 0.855 vs 0.936（-8.1pp 显著） | 同上 |
| 分解粒度负相关 | 1步-2.7pp / 2步-18pp / 3步-11.8pp | 08-27 pilot 最重要发现 |
| LLM Judge 格式偏见 | 同证据不同格式 5.7× 分差 | 消融实测（08-25 报告） |
| 人工盲评 | 16 题三方交叉，judge 与人工一致 62-75% | 08-25 报告 |
| Agentic 延迟 | Classic ×3.1（pilot）/ ×2.8（phase1） | 分轮口径 |
| Planner 降级链 | Model→retry→Rule→Classic，0 静默降级 | commit 4eaa109，逐样本 planner_source 可核验 |
| MCP 限流 | 10 QPS | Guava RateLimiter |

---

## 技术栈一句话

> "Java 17 / Spring Boot 3 / DDD 六边形 + Milvus 2.5（dense+BM25 RRF）/ bge-reranker-v2-m3（4090D GPU）/ GLM-4-plus 主路 + DeepSeek 备路（双路由熔断）/ MCP Server / React 19 SSE 流式 / 四层评测（IR + RAGAS + 拒答分离 + 多轮 Gate）/ paired A/B + 盲评 + bootstrap CI + 逐样本 planner_source 核验"
