# Agent 面试速查表（30 秒应答版）

> 基于 10 大短板修复后的终版状态。每题 30 秒内答完，追问再展开。

---

## 核心电梯稿（60 秒版）

> "我做了一个企业 RAG 平台，核心方法论是**评测驱动开发**。Classic 路径达到企业级水准（faith 0.971, Hit@5 92.5%）。Agentic 路径做了完整实现和 200 题×3 轮 paired A/B 对照，五轮系统性修复将差距从 -45pp 缩小到 -15pp（准确率 0.263→0.528），但结论是**当前语料规模不需要 Agent**——这就是'什么时候不用 Agent'的数据支撑。最有趣的发现是三方交叉验证揭示了 LLM-as-Judge 的系统性格式偏见（两个不同族 judge 一致率 91% 但与人工仅 62-75%）。"

---

## 10 个死亡问题 → 30 秒应答

### 1. "你的 Planner 和 if-else 有什么区别？"
> "分层的：Router Tier 处理 80% 简单题（零 LLM 调用、确定性路由），Planner Tier 处理 20% 多跳题（LLM 语义分解）。在 3076 chunks 上两者差距 <3pp，规则版是最优 ROI。语料扩到 10k+ 后 LLM 版才会拉开差距。"

### 2. "Replan 为什么只有 1 次？"
> "边际收益 <2pp 但成本 +30%。且去重签名和'生成新动作'存在设计冲突——已改为基于 Sufficiency 反馈的 uncovered 需求生成聚焦查询（天然与原查询不同），不再人工制造签名差异。"

### 3. "Sufficiency 判定器被架空了？"
> "已修复三个问题：①传入原始查询（原来传空串）；②三档分离 SUFFICIENT/DEGRADED_PARTIAL/INSUFFICIENT 可统计各自占比；③LLM Judge 异常从 fail-closed 改为 fail-open（判定器故障≠业务拒答）。"

### 4. "Token 预算为什么是 BigDecimal.ZERO？"
> "BudgetManager 的 denied 分支已实现，settle 路径已有 TokenEstimator 写入。配置层设 `RAG_AGENT_BUDGET_MAX_TOTAL_TOKENS=50000` 即激活（prod 默认已设）。dev 设 0 是因为不限制开发调试。"

### 5. "Checkpoint/lease 在防谁？"
> "单机 dev 通过 `lease.enabled=false` 禁用（零开销），多实例 prod 启用（防跨实例抢占 + 中断恢复）。这是'为扩展预留但不提前付费'——部署到 K8s 多副本时直接生效。"

### 6. "盲评 16 题能推翻 600 题 judge 结论？"
> "不能推翻——它揭示的是 direction（judge 可能有格式偏见）而非 proof（质量等同）。已扩大到 40 题并去除预填分。评测 runner 同时修复了拒答子串判 0（改为精确匹配）和截断偏差（统一 1200 字符）。"

### 7. "你最大的修复是把 Classic 塞回 Agentic？"（最关键）
> "锚定不是修回 Classic，是**混合架构**：原查询保底 + 子查询增量。锚定后 Agentic 引用 10.25 条 vs Classic 4.1 条，多出的 30% 是子查询额外覆盖。Slice A（多文档比较）差距仅 1pp，Slice S 盲评偏好反超。但全集差距 -15pp + 延迟 ×4 = 当前不该启用——这就是架构判断力。"

### 8. "Agentic 不支持多轮？"
> "已修复：PlannedAgentPipeline 注入 QueryContextualizer，execute() 入口做指代消解改写后传入 Planner。30 行代码复用 Classic 已有组件。"

### 9. "Agent 上线后怎么监控？"
> "5 类 Prometheus 指标已注册：sufficiency 分布（SUFFICIENT/PARTIAL/INSUFFICIENT 各占比）、replan 成功率、预算拒绝原因、E2E 延迟、per-component LLM 调用数。MCP 入口加了 RateLimiter（10 QPS）。"

### 10. "评测配置和生产配置不一样？"
> "设计意图：评测全开（需要评估 Agentic），生产全关（评测结论说当前不该开）。`application-prod.yml` 定义了生产标准（planner 全关 + token 预算 50k + recovery/lease 启用）。"

---

## 关键数字速记

| 指标 | 数值 | 一句话 |
|---|---|---|
| Classic faithfulness | 0.971 | 超过 0.90 生产门槛 |
| Classic Hit@5 | 92.5% | 3 轮一致 |
| Agentic V1→V5c | 0.263→0.529 | 五轮修复 +26.6pp |
| Phase 4 终版差距 | -15.5pp | 200 题×3 轮 |
| 人工盲评差距 | 0.0pp | 16 题三方交叉 |
| LLM Judge 格式偏见 | -6.3pp | 消融实测 |
| Slice A 差距 | -1.0pp | 多文档比较=Agent 设计场景 |
| Slice S 盲评偏好 | Agentic 54% | 简单题反超 Classic |
| Agentic 延迟 | Classic ×4 | p50 3s vs 12s |
| MCP 限流 | 10 QPS | Guava RateLimiter |

---

## 技术栈一句话

> "Java 17 / Spring Boot 3 / DDD 六边形 + Milvus 2.5（dense+BM25 RRF）/ bge-reranker-v2-m3（4090D GPU）/ GLM-4-plus 主路 + DeepSeek 备路（双路由熔断）/ MCP Server / React 19 SSE 流式 / 四层评测（IR + RAGAS + 拒答分离 + 多轮 Gate）/ paired A/B + 盲评 + bootstrap CI"
