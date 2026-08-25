# Agentic RAG 增量价值验证协议

## 目的

验证 Agentic 路径是否在 Classic RAG 已稳定的前提下，对“确实需要规划与多步检索”的问题产生可重复、可归因的净增益。实验不以功能数量、演示效果或单个成功案例作为启用依据。

## 零假设与实验单元

- 零假设：Agentic 相比 Classic 没有质量增益，或质量增益不足以抵消延迟、token、外部调用和失败面增加。
- 实验单元：同一个问题、同一 corpus/index snapshot、同一 embedding/reranker、同一生成模型版本和采样参数下的一对回答。
- 对照组：Classic RAG。
- 实验组：Agentic RAG（Planner → tools → Sufficiency Judge → Composer）。
- 采用 paired design；每题使用固定 run seed/temperature，并记录完整 trace、检索证据、工具调用、token、成本与延迟。

## 题集设计

保留一组 Simple control，防止 Agentic 损害普通问题；重点建立至少 60 题的 Complex challenge set：

| 切片 | 最低题数 | 必须具备的证据特征 |
|---|---:|---|
| 多文档/多版本比较 | 20 | 答案必须同时引用两个以上文档或版本 |
| 多约束排障 | 20 | 需要组合现象、配置、版本、错误信息中的至少三项 |
| 多步检索/信息拼接 | 20 | 单次 Top-K 无法直接覆盖全部答案要点 |
| Simple control | 20 | Classic 单次检索即可回答 |

题目必须 evidence-first 标注：标准答案、必要事实点、支持/反驳证据、文档版本、不可回答条件。冻结后记录 dataset SHA256 和 corpus/index fingerprint，禁止依据实验输出修改标准答案。

## 公平性控制

1. Classic 与 Agentic 使用同一知识库快照、候选池、reranker 与最终生成模型。
2. 主实验同时报告两种预算：等检索预算（相同总候选/工具次数）与产品预算（各走真实配置）。
3. Judge 不得看到系统名称、执行轨迹或答案顺序；A/B 顺序随机化，并加入位置互换复判。
4. 业务 LLM 与 Judge 物理隔离；抽取至少 20% 样本做双人盲审，报告 Judge/人工一致率。
5. 每个配置至少重复 3 次；报告 mean、standard deviation、paired bootstrap 95% CI，而非只报最好一轮。

## 主要指标与通过条件

主要质量指标为 Answer Correctness 和 Evidence Completeness；Faithfulness 与 Citation Accuracy 是安全门槛。Agentic 只有同时满足以下条件才算有增益：

- Complex challenge set：Answer Correctness 绝对提升 **≥5pp**，且 paired bootstrap 95% CI 下界 **>0**。
- 全集：Answer Correctness 绝对提升 **≥3pp**，或预先声明只对复杂路由启用。
- Evidence Completeness 绝对提升 **≥3pp**。
- Faithfulness 降幅不超过 **1pp**，Citation Accuracy 不下降。
- Simple control 不退化超过 **1pp**；否则路由必须确保简单题回到 Classic。
- 报告 p50/p95 latency、LLM/tool calls、input/output tokens 和估算成本；成本/延迟上限在实验前写入配置，实验后不得移动门槛。
- 失败、超时或预算耗尽必须自动 fallback Classic，并计入最终成功率和端到端延迟，不能从样本中剔除。

## 归因实验

通过主实验后，依次关闭 Planner、Sufficiency Judge、Replan、额外工具中的一个组件，做消融实验。只有能在复杂切片上稳定贡献增益的组件才保留。额外记录：

- plan validity / requirement coverage；
- tool success rate、重复检索率、无效步骤率；
- sufficiency false-positive / false-negative；
- 每题证据覆盖率随 step 的增长曲线；
- Agentic 获胜、持平、失败样本的根因分布。

## 启用流程

1. 离线冻结集连续 3 次通过全部门槛。
2. 仅为已验证的复杂切片打开 Router，Classic 继续作为默认路径。
3. 5% canary，监控质量代理指标、fallback、超时、成本和 p95 latency。
4. 达标后逐步扩大；任一安全门槛失败立即关闭 feature flag。

最终结论必须是以下三者之一：Agentic 有条件增益（限定切片启用）、无净增益（继续关闭）、证据不足（扩充题集），不能以“架构更先进”替代实验结论。
