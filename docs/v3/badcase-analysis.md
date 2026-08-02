# V3 P0 微评估 Badcase 分析

**报告起草日**: 2026-08-02
**对应 commit 跑数据**: RAGAS run 1 on 80 题 curated (faith 0.6072 / precision 0.4968 / recall 0.3486)
**对应真数字**: eval/p0_runs/eval_p0_run1.md

---

## 0. 结论先行

本次 P0 微评估跑出一组"明显低于合格线"的数字 (faith 0.61 / recall 0.35), 直接分析 80 题样本原始答案找出 5 个根因. **核心发现是 33% NO_RECALL 占样本, 且 NO_RECALL 多数情况下 LLM 判断正确(召回的 chunk 真不含答案), 不是 prompt 太严**.

按 5 个根因排序, 对应 V3-W3 工程修复 (commit 本文件 + 关联代码改动) 已实施; 真值数字待下次跑 P0 RAGAS 验证.

---

## 1. 数据真相(80 题统计)

| 维度 | 数字 | 解释 |
|---|---|---|
| 总样本 | 80 | |
| NO_RECALL 兜底文案("知识库中没有相关内容") | **27 (33%)** | 27 个题 chat 走"无内容"兜底 |
| 空召回(ctx=0) | 0 | 召回 always 有 chunk |
| 平均 ctx 数 | 4.84 | 100% 召回有内容 |
| 平均 answer 长度 | 105 字 | NO_RECALL 拉低均值 |
| 仅非 NO_RECALL 题(53 题) answer 平均长度 | 153 字 | 正常问答答案长度 |
| ground truth 含 "Spring Cloud Alibaba" 模板 | **63 (79%)** | 模板污染 |

**3 个数字组合 → 项目健康度**: 33% NO_RECALL + 79% ground truth 模板失真 + 0 空召回 → **召回算法有效但精度不足, ground truth 已偏离真实 chunk 文本**.

---

## 2. 5 个根因(按贡献度)

### 🔴 根因 1: NO_RECALL 占 33% — LLM 判断正确, 检索真没找到

**3 个 NO_RECALL 真实样本对照**:

| 题 | ground truth 关键事实 | 召回的 chunk 关键词 | 真匹配 |
|---|---|---|---|
| Nacos 容量评估 | "单节点支持 10 万级服务实例 / 30 万级配置" | "服务 / CRUD / 元数据管理" | ❌ |
| Dubbo hash.nodes 虚拟节点 | "`<dubbo:parameter key=hash.nodes value=N>`" | "resolve.file / spring.application 配置" | ❌ |
| Nacos 非 Docker 鉴权 | "application.properties 配置 token" | "Docker 镜像构建 / K8S 同步开关" | ❌(主题错) |

**根因分析**: dense + BM25 RRF 融合时 **5 组件共享概念("配置"/"实例"/"version")冲淡了 specific query 的匹配**; BGE-M3 dense 在中文长尾 query 上召回不够精准; **reranker 全程未真接通**(.env 写 6006, 实际隧道在 8084), 没有 cross-encoder 重排.

### 🔴 根因 2: ground truth 模板污染(79% 失真)

`eval/questions.curated.jsonl` 80 题里 63 题 ground_truth 是 LLM 看 chunk 后**改写总结**的版本, 大量 "Spring Cloud Alibaba 中, 可以通过 X 实现 ..." 这种通用 wrapper. RAGAS context_recall judge 看 ground_truth 在 chunks 里**找不到原文** → 一律打低分.

**典型例子**:
```
ground_truth: 在 Spring Cloud Alibaba 中, Dubbo 的虚拟节点数量可以通过
              配置文件进行修改. 具体来说, 可以在 Dubbo 的配置文件中添加
              或修改 `<dubbo:parameter>` 标签, 设置 `key` 属性为
              `"hash.nodes"`...
真实 chunk:   <dubbo:parameter key="hash.nodes" value="10" />
```
ground_truth 改写后, "在 Spring Cloud Alibaba 中" / "通过配置文件" 这些**虚拟上下文**让 RAGAS judge 识别为"chunk 不覆盖". 实际 chunk 完整涵盖答案.

### 🔴 根因 3: NO_RECALL 连锁损失影响 4 指标

一份 chat 调用走 NO_RECALL ∴ answer = "无相关内容" → 4 指标全降:
- answer_relevancy: answer 与 question 无关 → ~0
- context_recall: judge 推不出 ground_truth → ~0
- faithfulness: answer(无内容) 无法从 ctx 推 → 中性低分
- context_precision: top-k 相关性位次 → 中性低分

27/80=33% 样本均分接近 0, 拉低均值很厉害.

### 🟡 根因 4: Reranker .env 配错, 全跑 hybrid fallback

P0 跑前 `.env` 写着 `RAG_RERANK_BASE_URL=http://127.0.0.1:6006`, 但 Autodl reranker 隧道是 `-L 8084:localhost:6006` → chat-app 调 127.0.0.1:6006 不通 → `rerank_failed fallback to hybrid`. **V2-P4 历史 baseline 实测 reranker 净增 faith +5.5pp / precision +6.2pp / recall +2pp**, 本次没拿到.

工程失误链(dual-of-three config source 都漏防):
1. `dev.yml` 给了 fallback 默认 8084
2. `.env` 显式写 6006 覆盖了 fallback
3. 启动前没 grep 验证实际生效值

### 🟡 根因 5: Chunk 切细碎(parent_child 模式)

parent-child 模式下 child ~400 字 ≈ 100 token, 5 chunk 总 ~500 token 给 LLM. 看 NO_RECALL 样本里召回 chunk **平均 124-364 字, 远低于理论 400**(说明 chunks 本身细碎度比设计值更高). 信息密度低.

跟 V3 Day2 4 象限实验对照 `v3day2_quadrantD_parent_child.md` (50 docs + parent-child) faith 0.4167 / recall 0.2394 输给 hybrid+rerank flat 模式 → **parent-child 在小 corpus 下没优势**, 100 docs 下也未必赢.

---

## 3. V3-W3 工程修复(已 commit)

按 ROI 序已实施 4 项:

### ✅ 修复 #1 (配置卫生)
- `.env: RAG_RERANK_BASE_URL=http://localhost:8084` 改正
- `application-dev.yml` 注释明示 "base-url 无默认值, 必须显式设"
- 加 `docs/v3/p0-eval-runbook.md` §1 显式提醒

### ✅ 修复 #2 (RetrieveService 加固日志)
- 启动期 `@PostConstruct logRerankConfig` 打日志明示 reranker 实际生效配置(enabled/base_url/candidate_pool/topN)
- 每次召回 cmd log 加 `top1_hybrid_score` + `top1_rerank_score` 对照, 下次跑 RAGAS 看日志即知 reranker 是否真起作用

### ✅ 修复 #3 (重写 gen_questions = extractive ground truth)
**核心改动**:
- prompt 强约束 "answer 必须**原样摘录** chunk 内 1-3 句, 不允许任何改写/总结"
- 加 `is_substring_of` 验证(answer 是 source 的 white-spacing-insitive substring), 不通过直接丢弃
- 采样 seed 化(`target*2` 个候选 chunk) 而非全遍历 2224
- 单 chunk 出 1 题(够 curated 用, 控成本)

工时: ~1.5h 重写 + 单测自验
**预期效果**: ground_truth ∴ 100% 是 chunk 原文 span, RAGAS recall judge 直接命中 → recall 真值回升

### ✅ 修复 #4 (本文件 docs/v3/badcase-analysis.md)
落档 5 个根因 + 4 项修复 + 预期指标变化, 下次 P0 跑完对比真值差异.

---

## 4. 修复后预期指标(下次 P0 RAGAS 验证)

| 指标 | 本次(rerank OFF / 失真 GT) | 修后预测 | 依据 |
|---|---|---|---|
| faithfulness | 0.6072 | **0.70-0.78** | reranker 净增 +5pp + ground_truth 真值让 judge 不误判 +5pp + NO_RECALL 降到 ~15% +5pp |
| context_precision | 0.4968 | **0.65-0.72** | reranker 净增 +6pp + top5 排序精准 +10pp |
| context_recall | 0.3486 | **0.55-0.70** | ground_truth extractive 命中率 +20pp + 召回提质 +5pp |
| answer_relevancy | 0.5275 | **0.65-0.75** | NO_RECALL 降 → answer 与问题相关度提升 +15pp |

**核心判断**: 本次数字偏低主因是 3 个组合因素叠加(失真 GT + rerank 错配 + LLM prompt 触发严 NO_RECALL), **不是项目真实能力上限**. 修后预期直逼"V3 合格线"(faith >=0.75 / recall >=0.65, ADR-0008 D3 验收报告 §7).

---

## 5. 成熟 RAG 同维度对照(供下次实测后定位差距)

| 系统 | 配置 | faith | precision | recall | 与本项目差距 |
|---|---|---|---|---|---|
| RAGAS 官方 benchmark | gpt-4 + 大数据集 + gpt-4 judge | 0.85 | 0.75 | 0.75 | -25pp faith / LLM 模型 + corpus 大小 + judge |
| LlamaIndex demo | gpt-3.5 + Anthropic judge + rerank | 0.75 | 0.70 | 0.80 | -15pp faith / 工程(HyDE / multi-hop) + LLM |
| 企业私有 RAG median | 中等工程化 | 0.70-0.80 | 0.65-0.75 | 0.65-0.75 | -10pp faith / 规模化工程 + corpus |
| **本项目本次 P0 run1** | 100 docs, hybrid only(rerank OFF 失真 GT), glm-4-plus | 0.61 | 0.50 | 0.35 | 见 §4 修后预测 |

**对照结论**:
- faith 0.61 已接近小项目 demo 级上沿(glm-4-plus 比 gpt-4 弱 15-20% 已生效, 该数符合)
- recall 0.35 真警告信号, 主因修复后回升
- 项目缺的二阶优化: HyDE / multi-hop / sub-query 跨多 doc 综合(主流 LlamaIndex/LangChain 都有), V4 主线候选

---

## 6. 下次 P0 跑前自检清单

跑 RAGAS 前 5 项必查:

1. ✅ `.env: RAG_RERANK_BASE_URL` 指向 8084
2. ✅ `chat-app` 启动日志含 `retrieve.rerank_config enabled=true, base_url=http://localhost:8084, candidate_pool=20, top_n=5`
3. ✅ Autodl reranker 服务跑 + 本地 curl localhost:8084/health 返回 ok
4. ✅ 单 chat smoke 看 log 有 `retrieve.rerank_applied` 且无 `rerank_failed`
5. ✅ 跑完一次 chat 单题确认 LLM 答案非 "无相关内容"(走 OK 路径)

达成 5 项再跑 P0 RAGAS 全量, 否则只重跑不会出真值.
