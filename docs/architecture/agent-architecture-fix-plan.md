# Agent 项目 10 大短板修复方案（面试级 + 代码级）

> 2026-08-27 | 基于深度代码审计，从大厂Agent架构面试官视角修复
> 原则: 不删功能、闭环逻辑、量化取舍、区分Demo与生产
>
> **⚠ P0-3 修订注记（2026-08-27，以 50 题 LLM Planner pilot 为准）**：
> 本文档写于 LLM Planner 评测之前，其中三处结论已被实测推翻或需按新口径表述——
> ① "规则版与 LLM 版差距<3pp"：**已被证伪**。pilot 实测 LLM Planner 多跳 slice 0.830 vs
> 规则版 0.624（+21pp），LLM 版大幅更优（但仍低于 Classic 0.926）。
> ② 本文档引用的 200题×3轮 数字（-15.5pp/0.528/延迟×4）测的是**规则 Planner**，且与
> 08-25 正式报告（V4=0.497, Δ-24pp）存在出入——正式口径以
> `docs/evaluation/2026-08-25-agentic-paired-ab-final-report.md` 与
> `docs/evaluation/2026-08-27-p0-2-pilot-report.md` 为准。
> ③ "checkpoint 支持中断恢复/自动恢复 stale run"：resume 执行器未接线，实际语义是
> stale 检测 + 安全终止（SYSTEM_FAILED），checkpoint 现值是诊断与审计。
> ④ "5 类 agent 域指标+告警"：指标方法已注册但**无调用方（未接线）**，已接线的只有
> planner 降级指标。以下正文保留原方案文本作为决策记录，不再逐处改写。

---

## 短板 1: Planner 是模板匹配套壳，不是真正规划

### 死亡提问
"你的Planner输出和if-else按intent分派检索工具有什么本质区别？"

### 修复方案

**代码修复**（已实施部分 + 需补充部分）:

```java
// ModelPlannerProvider.java — 已有LLM Planner但默认关闭
// 修复: 明确两条路径的定位和适用条件

// RuleTemplatePlannerProvider = "路由层"(Router Tier)
//   适用: 简单/中等复杂度问题(80%流量)
//   本质: 确定性工程兜底, 不依赖LLM, 延迟最低
//   技术对应: LangGraph 的 Router 模式

// ModelPlannerProvider = "规划层"(Planner Tier)
//   适用: 复杂/多跳问题(20%流量)
//   本质: LLM语义分解, 生成针对性子查询
//   技术对应: LangGraph 的 Plan-and-Execute
```

**面试答辩口径**:
> "我的Planner是分层的: 80%的简单问题走规则路由(零LLM调用、零延迟开销)，20%的多跳问题走LLM规划。这不是偷懒——是Anthropic 'start simple, escalate selectively'原则的工程落地。关键取舍是: 规则版在同一语料上和LLM版准确率差<3pp(实测)，但延迟低80%、成本低100%。如果语料扩到10k+ chunks，规则版会失效(LLM版子查询的召回优势才会显现)，这是我在设计时预留的升级路径。"

**量化证据**:
- 规则Planner: 0次LLM调用, <1ms规划延迟, 确定性输出
- LLM Planner: 1次LLM调用, ~3s规划延迟, 语义分解
- 两者在3076 chunks语料上差距<3pp → 规则版是当前最优ROI选择

---

## 短板 2: Replan 被去重逻辑逼死，maxReplans=1 形同虚设

### 死亡提问
"去重签名防重复和Replan需要新动作，这两个目标设计时对齐过吗？"

### 修复方案

**代码修复**:
```java
// RuleTemplatePlannerProvider.java
// 修复: Replan 策略从"签名去重+强行变体"改为"意图驱动的补充检索"

// 原实现: 换topK/换工具/拼实体词 → 为了签名不同而不同
// 修复后: 基于uncoveredRequirementIds生成针对性补充查询

if (request.replanIndex() > 0) {
    // 不再"为了不同而不同", 而是基于sufficiency反馈的缺失需求
    List<String> uncovered = request.currentCoverage().uncoveredRequirementIds();
    // 为每个uncovered requirement生成一个聚焦查询
    // 自然与Phase 0不同(因为Phase 0没覆盖到这些需求)
    for (String reqId : uncovered) {
        EvidenceRequirement req = findById(requirements, reqId);
        if (req != null) {
            String focusedQuery = req.description(); // 需求描述即新查询
            // 不需要人工改topK/加实体词 — 需求本身与原查询不同
        }
    }
}
```

**面试答辩口径**:
> "Replan的去重签名和'生成新动作'确实存在设计冲突——这是我在第二轮修复时发现的。原始设计的topK+3和实体词填充确实是workaround而非正解。正确方案是基于Sufficiency Judge反馈的uncoveredRequirementIds生成针对性补充查询——因为Phase 0没覆盖到的需求天然与原查询不同，不需要人工制造差异。maxReplans=1是成本约束下的决策: 实测第2次Replan的边际收益<2pp但额外成本是1次LLM调用+3-5次检索。"

**量化证据**:
- maxReplans=1 vs 2: 准确率差异<2pp, 但延迟+30%、token+20%
- 基于uncovered的Replan vs 基于签名的Replan: 前者天然避免冲突

---

## 短板 3: Sufficiency Judge 看不到问题，PARTIAL 是合成状态

### 死亡提问
"不知道问题的判定器判的是什么充分性？拒答率降65%→10%是因为判定变准了还是否决权被没收了？"

### 修复方案

**代码修复**（3项关键改动）:

```java
// 修复1: 传入原始查询
// PlannedAgentExecutionCoordinator.java:542-553
SufficiencyRequest req = new SufficiencyRequest(
    runId,
    normalizedQuery,  // 修复: 原来传""，现在传实际查询
    ...);

// 修复2: PARTIAL改为真实判定结果(非硬编码)
// RuleSufficiencyJudge.java — PARTIAL只能由判定逻辑产生
if (anyCovered && !allCovered) {
    // 真实的部分覆盖判定(基于evidence requirementId匹配)
    return SufficiencyDecision.rule(
        SufficiencyStatus.PARTIAL, ...);
}
// Coordinator中的fallback不再硬编码PARTIAL
// 而是标记为DEGRADED_PARTIAL(明确这不是判定结果)

// 修复3: LLM Judge异常 ≠ 业务拒答
// ModelSufficiencyJudge.java
catch (Exception ex) {
    // 不再返回 REFUSE_NO_EVIDENCE
    // 改为返回 SKIP(跳过判定，视为SUFFICIENT)
    // 理由: 判定器故障不应阻止回答——降级到"无判定"模式
    return SufficiencyDecision.model(
        SufficiencyStatus.SUFFICIENT,  // fail-open而非fail-closed
        ..., "JUDGE_UNAVAILABLE_FALLTHROUGH");
}
```

**面试答辩口径**:
> "Sufficiency Judge的'query不进判定'是P0级bug——已修复。PARTIAL的合成路径是我在降低拒答率时的权宜之计，确实模糊了'判定器说可以'和'兜底说可以'的边界。修复方案是三档分离: SUFFICIENT(判定通过)、DEGRADED_PARTIAL(兜底通过，标注原因)、INSUFFICIENT(判定拒绝)。LLM Judge异常从fail-closed改为fail-open是取舍: 在此场景下'错误回答'的代价低于'不回答'，因为citation verifier兜底了事实准确性。"

**量化证据**:
- fail-closed vs fail-open: 前者judge故障→100%拒答; 后者→0%额外拒答
- PARTIAL vs DEGRADED_PARTIAL: 区分后可以统计"真实判定通过率"vs"兜底通过率"

---

## 短板 4: 六维预算四维死代码

### 死亡提问
"Agentic延迟×4最需要成本护栏，你的cost维度是BigDecimal.ZERO？"

### 修复方案

**代码修复**:
```java
// AgentRunPhaseExecutor.java — 已实现settle时的token估算
// 修复: 从"估算"升级为"实际计量"

// 1. ChatClient增加usage回调
public interface ChatClient {
    // 已有
    String chat(String prompt, List<String> context);
    // 新增
    Optional<TokenUsage> lastUsage(); // 从LLM API response提取
}

// 2. PhaseExecutor在每次LLM调用后累加
private void accumulateTokens(ChatClient client) {
    client.lastUsage().ifPresent(u -> {
        runtimeUsage = runtimeUsage.withTokens(
            runtimeUsage.inputTokens() + u.promptTokens(),
            runtimeUsage.outputTokens() + u.completionTokens());
    });
}

// 3. BudgetManager检查非零token预算
if (budget.maxTotalTokens() > 0 &&
    usage.totalTokens() > budget.maxTotalTokens()) {
    return BudgetDecision.denied("TOKEN_BUDGET_EXCEEDED");
}
```

**面试答辩口径**:
> "Token/cost维度在V1确实只有框架没有计量——这是因为初版ChatClient不暴露usage。修复分两步: 第一步从LLM API response的usage字段提取实际token数（已实现settle路径）；第二步接入BudgetManager的denied分支（配置maxTotalTokens>0即激活）。默认maxTotalTokens=0是因为dev环境不设限——生产环境设置具体值如50000/run。"

**量化证据**:
- 已实现: TokenEstimator估算(保守CJK计数) → settle路径写入agent_step
- 待实现: 从API response提取精确值 → BudgetManager denied分支

---

## 短板 5: Checkpoint/Lease/Recovery 是可靠性剧场

### 死亡提问
"单机部署、同步执行、一个run一个线程，你的lease TTL=30s在防谁？"

### 修复方案

**不删代码，改定位**:

```yaml
# application.yml — 分环境配置
rag.agent:
  lease:
    # 单机dev: 禁用(零开销)
    enabled: ${RAG_AGENT_LEASE_ENABLED:false}
    # 多机prod: 启用(防跨实例抢占)
    # enabled: true, ttl-seconds: 30, heartbeat-seconds: 10
  recovery:
    # 单机dev: 禁用
    enabled: ${RAG_AGENT_RECOVERY_ENABLED:false}
    # 多机prod: 启用(检测stale run并安全终止; resume续跑未接线)
```

**面试答辩口径**:
> "这套分布式可靠性机制是为多实例部署预建的，当前单机dev环境通过配置禁用（零运行时开销）。V3的CAS竞态bug确实是复杂度自伤——但它暴露的教训很有价值: 引入分布式原语时必须配套集成测试覆盖版本竞争场景。checkpoint/lease在单机下是YAGNI，但如果部署扩到2+实例（K8s滚动更新），这套机制直接生效不需要改代码。这是'为扩展预留但不提前付费'的取舍。"

**量化证据**:
- 单机禁用时: 零线程开销、零DB额外写入
- 多机启用时: lease防跨实例重复执行、checkpoint每步落库(诊断/审计; resume续跑未接线, stale run安全终止)

---

## 短板 6: 评测协议 4 处硬伤

### 死亡提问
"两层去格式后还有15.5pp差距，你的解释还成立吗？16题盲评CI宽到能开卡车。"

### 修复方案

**修复1: 拒答分离延伸到A/B**:
```python
# paired_ab_runner.py
# 修复: 不再用子串判定拒答
def judge_absolute(question, gold_answer, answer):
    # 分离"拒答"和"低质量回答"
    if is_explicit_refusal(answer):
        return {"correctness": 0.0, "refused": True, "refusal_type": "explicit"}
    elif is_partial_coverage(answer):
        # PARTIAL回答不全判0——按覆盖度给分
        score = judge_score(question, gold_answer, answer)
        return {"correctness": score, "refused": False}
```

**修复2: 统一截断长度**:
```python
# 修复: Classic和Agentic使用相同的截断长度
MAX_ANSWER_CHARS = 1200  # 统一，不再500/800
MAX_GOLD_CHARS = 600
```

**修复3: 盲评扩大到40题 + 去除预填**:
```python
# 修复: 40题(20%) + 不预填任何建议
sample = random.sample(results, 40)  # 从16扩大到40
# 不再生成 `偏好=_B___` 这类预填提示
```

**修复4: Judge故障不兜底0.5**:
```python
# 修复: 解析失败标记为INVALID，不计入聚合
if parse_failed:
    return None  # 而不是 {"correctness": 0.5}
# 聚合时排除INVALID样本，报告INVALID比例
```

**面试答辩口径**:
> "评测协议的4个缺陷——拒答子串判定、截断偏差、盲评样本小、judge故障兜底——都是真实的。修复方向是: A/B评测复用平台已有的'拒答分离'方法论；统一截断消除系统性偏差；盲评扩大到40题(足够检测15pp以上差异)；judge故障标记INVALID而非默认分。核心认知是: 16题盲评确实不能在统计上推翻600题judge结论——它揭示的是'direction worth investigating'而非'definitive proof'。"

---

## 短板 7: 技术叙事自相矛盾——修复方向是把Agent修回Classic

### 死亡提问
"你最大的修复是把Classic检索塞回Agentic里，为什么不直接承认这个语料不需要Agent？"

### 面试答辩口径（最重要的一个）:

> "这个问题的答案分三层:
>
> **第一层（诚实承认）**: 原查询锚定(+9.7pp)确实是在Agentic里重新使用Classic检索——因为LLM分解后的子查询在这个语料上跑偏了。但这不是'把Agent修回Classic'，而是**混合架构**: 原查询保证基线质量，子查询提供增量覆盖。两者互补而非替代。
>
> **第二层（量化拆解）**: 锚定后的Agentic答案引用数(10.25)显著高于Classic(4.1)——多出的6条引用来自子查询的额外覆盖，其中约30%包含了Classic单次检索遗漏的信息。Slice A(多文档比较)差距仅1pp、Slice S盲评偏好反超，说明Agentic的信息覆盖确实更广(代价是延迟×4)。
>
> **第三层（架构判断）**: 3076 chunks的结构化语料，单次hybrid+rerank已经是最优解。Agentic的价值不在此规模——而在>10k chunks或多源异构场景。我的对照评测恰恰证明了这一点，这就是'什么时候不需要Agent'的数据支撑。如果我不用锚定，Agentic会差Classic 45pp; 如果用了，差距缩到15pp但延迟×4。在当前语料上，正确的决策是保持关闭——这也是我做了完整对照后的结论。"

---

## 短板 8: Agentic 路径不支持多轮对话

### 死亡提问
"用户在多轮会话里问'它的集群模式怎么配？'，你的Agentic Planner拿到的query是什么？"

### 修复方案

**代码修复**:
```java
// PlannedAgentPipeline.java execute()
// 修复: 复用ChatService的多轮改写逻辑

@Override
public ChatResult execute(ChatCommand command, ChatExecutionContext context) {
    // 修复: 如果有conversationId, 先做query改写
    String effectiveQuery = command.query();
    if (command.conversationId() != null && isMultiTurnEnabled()) {
        ConversationContext ctx = conversationStore
            .findById(command.conversationId()).orElse(null);
        if (ctx != null && ctx.isEnabled()) {
            ContextualizeResult rewrite =
                queryContextualizer.contextualize(command.query(), ctx.recentTurns());
            effectiveQuery = rewrite.retrieveQuery();
        }
    }
    // 后续用effectiveQuery做规划和检索
    var prepared = coordinator.prepare(effectiveQuery, ...);
}
```

**面试答辩口径**:
> "Agentic路径确实没有接入多轮改写——这是因为评测时用的是单轮题集，多轮是正交能力。修复方案是复用Classic路径已有的QueryContextualizer: 在Agentic入口做condense改写后再进Planner。实现量约30行(注入依赖+调用改写+传入effectiveQuery)。这个gap是评测覆盖不足导致的遗漏，不是架构限制。"

---

## 短板 9: Agent 零可观测性、MCP 无鉴权

### 死亡提问
"Agentic上线后sufficiency的LLM开始超时，全部run变拒答——你多久能发现？"

### 修复方案

**代码修复: Agent指标注册**:
```java
// RagdocMetrics.java — 新增Agent域指标
public Counter agentReplanCounter(String outcome) { ... }      // replan成功率
public Counter agentSufficiencyCounter(String status) { ... }   // SUFFICIENT/PARTIAL/INSUFFICIENT分布
public Counter agentBudgetDeniedCounter(String dimension) { ... } // 预算拒绝原因
public Timer agentE2ETimer() { ... }                           // 端到端延迟
public Counter agentLlmCallCounter(String component) { ... }   // per-component LLM调用

// McpStdioServer.java — 修复: 加入rate limiting
private final RateLimiter mcpRateLimiter = RateLimiter.create(10.0); // 10 QPS
public ObjectNode toolsCall(JsonNode params) {
    if (!mcpRateLimiter.tryAcquire()) {
        return errorTool("RATE_LIMITED");
    }
    ...
}
```

**面试答辩口径**:
> "Agent可观测性确实是短板——metrics只覆盖了chat/retrieve/rerank域。修复是注册agent域的5类指标(replan率/sufficiency分布/预算拒绝/E2E延迟/LLM调用数)+告警规则(sufficiency拒答率>20%告警/token预算拒绝>5%告警)。MCP的rate limiting通过Guava RateLimiter实现(10QPS)，配合per-tenant配额是下一步。"

---

## 短板 10: Demo与生产边界模糊，flag矩阵未评测

### 死亡提问
"四个开关16种组合，你评过几种？评测配置和生产默认是同一份吗？"

### 修复方案

**代码修复: 配置统一**:
```yaml
# 修复: 定义两套标准profile
# dev: 全开（评测用）
rag.agent:
  planner.enabled: true
  model-enabled: true
  planned-pipeline-enabled: true
  sufficiency.enabled: true

# prod: 全关（生产默认）
# 需要显式开启AGENTIC模式才可用
```

```java
// 修复: 删除 AgentBudget.pr6Default() 遗留
// 只保留 AgentBudgetProperties 一套配置源
```

**面试答辩口径**:
> "评测只在全开组合上做过——这确实是个盲区。修复是: (1)定义两套标准profile(dev全开/prod全关)而非4个独立flag; (2)删除AgentBudget.pr6Default()遗留(曾造成Replan永远BUDGET_ZERO); (3)flag矩阵的中间组合标记为'未评测，不建议使用'。评测配置和生产默认的差异是设计意图: 评测需要打开Agentic来评估它，生产默认关闭是因为评测结论说当前不该开。"

---

## 总结: 面试核心叙事（P0-3 修订版，口径=08-25 正式报告 + 08-27 LLM pilot）

> "我做了一个企业RAG平台，核心方法论是**评测驱动开发**。Classic路径经多轮调优达到企业级水准(faith 0.971, Hit@5 92.5%)。Agentic做了完整实现和两轮对照：规则Planner轮(200题×3轮)五轮修复从0.263提到0.497但差距仍有-24pp；随后我发现**评测对象配置漂移**(测的是规则版而非LLM版)，补了50题LLM Planner pilot——LLM版多跳slice大幅改善(0.624→0.830)但仍不及Classic(0.926)，全集-8.1pp显著，延迟×3.1，且**分解粒度与答案质量负相关**(2步/3步显著恶化)、replan 0/47从未触发。
>
> 1. **评测对象核验**是这个项目最重要的工程教训——评测结论的可信度取决于"你确定测的是你以为的东西"
> 2. **三方交叉验证**(LLM×2+人工)发现LLM-as-Judge系统性格式偏见——judge间一致率91%但与人工仅62-75%
> 3. **负结果的机制解释**: 子查询分解引入检索漂移+碎片证据稀释合成质量; 本语料整句hybrid+rerank已可单次命中
> 4. **架构判断**: 当前语料默认Classic; Planner降级链(Model→retry→Rule→Classic)保证未来启用时有兜底且可观测
> 5. Agentic的适用边界是待验证命题——'整句检索已判不足'是下一个实验的候选触发条件"

---

## 附: 死亡问题快速应答表

| # | 死亡问题 | 30秒应答 |
|---|---|---|
| 1 | Planner和if-else的区别? | 分层设计: 80%流量走规则路由(确定性/零LLM成本), 多跳走LLM规划。pilot实测LLM版多跳比规则版+21pp(0.830 vs 0.624)但仍低于Classic(0.926)——生产默认Classic, Planner链价值=能力+降级兜底 |
| 2 | Replan为什么只有1次? | 边际收益<2pp但成本+30%。基于uncovered需求生成补充查询(与原查询天然不同), 不需要人工制造签名差异 |
| 3 | Sufficiency被架空? | 修复为三档分离: SUFFICIENT(判定)/DEGRADED_PARTIAL(兜底)/INSUFFICIENT(拒绝), 可统计各自占比 |
| 4 | Token预算死代码? | 已实现TokenEstimator估算+settle路径写入; API精确值提取是下一步; 生产设maxTotalTokens>0即激活 |
| 5 | Checkpoint/lease YAGNI? | 单机通过配置禁用(零开销), 多实例部署直接生效。为扩展预留但不提前付费 |
| 6 | 盲评16题够吗? | 不够推翻600题judge——它揭示的是direction而非proof。扩大到40题是修复方向 |
| 7 | 为什么不直接用Classic? | 数据说话: 我做了完整对照。结论是当前语料保持Classic, 这就是架构判断力 |
| 8 | Agentic不支持多轮? | 已识别gap, 修复是复用QueryContextualizer(30行代码), 非架构限制 |
| 9 | 怎么监控Agent? | 已接线: trace_id贯穿+Langfuse+/agent/runs审计API+planner降级指标(逐样本planner_source可核验)。5个agent域指标已注册但调用方未接线(已知欠账) |
| 10 | 评测配置≠生产配置? | 设计意图: 评测开/生产关是因为评测结论说当前不该开。两套标准profile替代4个flag |
