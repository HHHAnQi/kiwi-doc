# V3 Baseline (GLM-4-plus judge + Autodl Reranker + curated 集)

**生成日期**: 待 V3 第 0.5 周 E2 跑完后填实数
**judge LLM**: glm-4-plus + thinking:{type:disabled}(commit d56a3e9)
**corpus**: 50 docs(暂用), V3 第 1 周 parser 拆完后重灌 100+ docs 时更新此 baseline
**question 集**: eval/questions.curated.jsonl(80 题过滤后, V3 跑前 30 题)

## 汇总指标

(待填: 跑 eval 拿数字后回填, 4 指标 + 置信区间)

- faithfulness: 0.6700 # 占位, 等 E2 跑完替换
- answer_relevancy: 0.6200
- context_precision: 0.7200
- context_recall: 0.5700

## failure case study(D4)

(占位, E2 跑完补 bottom-3 题根因)

## 噪声 ±X pp(D2)

V2 验收报告 §3.2 在 GLM-4-flash 下实测 ±1.7pp(30 题 × 3 跑 StDev)。
GLM-4-plus judge 在 curated 30 题下的噪声 baseline 待 E2 实测定。

---

**这是 placeholder 参考文件, 真数字等首次 baseline 跑完后回填。**
**CI eval-regression.yml 暂时跑时阈值 3pp 保守, V3 第 1 周末定稿后收紧。**
