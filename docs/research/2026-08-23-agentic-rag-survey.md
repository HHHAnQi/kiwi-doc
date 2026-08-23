# Agentic RAG 成熟实现调研（2025-2026）

> 2026-08-23 调研，输入 ADR-0012 的方案校准。来源：LangGraph/LlamaIndex/微软生态源码与
> 官方文档、Anthropic/OpenAI/Google 官方工程博客、Self-RAG/CRAG/Adaptive-RAG/Search-R1/
> Plan×RAG 论文、τ-bench/BFCL/GAIA/BrowseComp 基准、Perplexity/Glean 工业口径。

## 1. 业界收敛的 Agentic RAG 参考架构

```
查询 → 路由器(是否检索/哪条策略/多复杂)          [Adaptive-RAG / Anthropic Routing]
     → 规划器(分解为子查询 DAG)                  [Plan×RAG / Deep Research 计划阶段]
     → 并行执行(向量+关键词+图+子agent 扇出)      [Anthropic orchestrator-workers]
     → 自反思(证据充分? 检索质量三档路由)          [Self-RAG / CRAG / sufficiency judge]
     → (不足 → 增量 Replan / 换源; 足够 → 早停)
     → 综合(带引用 + 冲突标注) + 逐句 grounding   [Perplexity/Toloka 口径]
护栏: 步数 × token × 并发三重预算; 85% 预算强制收尾; 可组合终止条件
```

三条生产路线并存：Anthropic 多智能体编排（高价值任务，token ×15）、OpenAI 单智能体
端到端 RL（Deep Research）、Gemini 可编辑计划+异步（用户在环）。共同点：**计划显式化 +
检索迭代 + 综合验证 + 显式预算**。

## 2. 关键设计决策（按对我们项目的价值排序）

| # | 设计 | 出处 | 对 kiwi-doc 的含义 |
|---|---|---|---|
| 1 | **路由是成本第一杠杆**：token 用量+模型选择解释 95% 性能方差；按复杂度伸缩投入 | Anthropic | TaskRouter + 置信度门已就位，方向正确 |
| 2 | **上下文卫生**：每步工具输出先压缩再回传（compress_research）；写作收敛到最后单次调用 | LangGraph open_deep_research | 需增加：步骤级证据压缩（防上下文爆炸） |
| 3 | **预算三件套**：步数×token×并发上限 + 85% 阈值强制收尾（Beast Mode 保底必有输出） | open_deep_research / jina DeepResearch | 现有 max-plan-steps=3 + max-replans=1 需补 token 预算与强制收尾 |
| 4 | **终止做成可组合原语**（MaxMessage/TokenUsage/Timeout 按位或） | AutoGen TerminationCondition | 值得借鉴的代码结构 |
| 5 | **计划外化为 DAG，兄弟节点并行**：并行工具调用省 90% 研究时间 | Plan×RAG / Anthropic | 现 Planner 顺序执行；Phase 2 可加并行扇出 |
| 6 | **检索算子按问题形态分化**（Local/Global/DRIFT；三档 CRAG 路由） | GraphRAG / CRAG | 三路检索工具已有雏形；可加"全局摘要型"问题处理 |
| 7 | **<8-10 工具时单 agent 更优**；多 agent 收益只在广度任务 | LangGraph/AutoGen 实践 | 验证了 ADR-0012 不上多 agent 的决策 |
| 8 | **评估器-优化器循环**（verifier 触发再检索）防"自信但错误" | Anthropic / CRAG | CitationVerify + SufficiencyJudge 已有，需闭环到"触发再检索" |
| 9 | **Bitter Lesson**：harness 用可拆除的低层积木，勿绑重型抽象 | LangGraph 博文 | 与我方自研轻量协调层一致 |
| 10 | **RL 训练检索策略**（Search-R1 系：何时搜/搜什么/搜几次变成可训练策略） | 论文 | 远期方向：桌面「微调项目」GRPO 资产可对接 |

## 3. 评测口径（ADR-0012 Phase 1 评测协议升级依据）

- **pass^k**（k 次全过率）而非单次 pass：agentic 路径的稳定性是隐藏短板
  （τ-bench：GPT-4o pass@1 0.42 → pass@4 0.20）→ 对照实验每题跑 3-5 次
- **准确率/单题成本/时延三维并列**（xbench 口径）+ **每被接受答案的成本**
  （未通过质检的输出不计有效）→ agentic 增益 > 成本乘数才值得启用该路径
- **组件级过程评测**（RAGCap-Bench：规划/证据抽取/有据推理/噪声鲁棒四项中间能力）
  → 端到端分无法归因，需按失败模式聚类 trace
- **逐句 grounding**（Perplexity/Toloka：每句话必须被引用支持，整体打分会漏）
  → 与现有 faithfulness 互补
- **工具选择量化**（BFCL：AST 校验 + 沙箱执行 + 幻觉调用检测）→ Agent 轨迹评测单元层
- 固定语料多跳基线：MuSiQue 最难且防"伪多跳"；SimpleQA 作幻觉下限

## 4. 对 ADR-0012 的修订项

1. Phase 1 评测协议升级：多跳题 20 题 × 3-5 次重复报 pass^k；加单题 token 成本与
   p50/p95 时延列；trace 按失败模式聚类（引用不支持/漏检索/循环/超步）。
2. Phase 1 增加"85% 预算强制收尾"：预算耗尽时基于已有证据强制生成答案（保底必有输出），
   而非 REFUSED。
3. Phase 2 增加步骤级证据压缩（工具输出 → 结论化摘要后再进协调器上下文）。
4. Phase 2 并行执行：无依赖的 plan step 并行扇出（工具只读，天然可并行）。
5. 好消息确认：不上多 agent（工具 <10）、自研轻量协调层、Plan-Execute+单次 Replan、
   路由分层——四个既有决策全部与业界共识一致，无需返工。
