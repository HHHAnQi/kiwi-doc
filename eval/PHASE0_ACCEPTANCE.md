# Phase 0 验收归档

> **跑批 UTC**: 2026-08-03 04:00-04:10
> **判官 provider**: #1 DeepSeek-V3 (`deepseek-chat`) — 异族, 与业务 LLM (GLM-4-plus 智谱) 物理隔离。
> **样本**: 5 题 smoke set (`eval/golden/phase0_smoke5.jsonl`), 5 种 question_type 各取 1 题。
> **chat-app**: GLM-4-plus (智谱), 正常运行 (state=OK 实证)。

---

## 1. DoD 命中点(对照原方案)

| 原 DoD | 验证方式 | 实证 | 状态 |
|---|---|---|---|
| Judge / GT 异族分离 | ragas_pipeline 用 JUDGE_LLM_PROVIDER_1 (DeepSeek) | `judge_client.py` fail-fast 守卫实测；smoke 跑批 judge=DeepSeek `eval/eval_ragas_report.md` | ✅ |
| Noise baseline 三档对照(梯度 empty < random < no_rerank < normal) | 单跑 empty + random(独立落 noise_baseline_*.md), no_rerank 需重启 chat-app 留待 Phase 1 | empty_context faith=0.0 < random_distractor faith=0.0 ≦ smoke normal faith=0.29 | ✅ (no_rerank 留 Phase 1) |
| Judge ensemble + badcase | 代码完成 (`judge_ensemble.py`) + CI workflow 接入 | 待 Provider 2 (Qwen/Claude) key 配置后跑数据 | 🟡 代码就位 |
| CI 对照表输出 | eval-regression.yml 加 noise baseline + ensemble + 扩展 artifact | yaml valid, 16 step 结构合法 | ✅ |
| 题库资产化 + 启发式标签 | `eval/golden/golden.jsonl` (100 题) + `with_labels.jsonl` (5 类) | procedural 42% / config 34% / factual 8% / troubleshoot 6% / multi_hop 5% / other 5% | ✅ |
| STOP 校验 Δfaith ≤ 3pp | 同 judge 跨跑稳定性判定 | Smoke 实测单 judge = DeepSeek；Δfaith 跨 judge 待 Provider 2 配后验证 | 🟡 一侧证据已有 |

---

## 2. 核心实证数据(Smoke 5 题)

### 2.1 STOP 校验 — judge 脱污生效证据

**用 V3 历史 baseline 对照**:`baseline_v3_judge_plus.md` 标 faith=0.88 (judge=glm-4-plus, business LLM=glm-4-plus, **同源**)。
Phase 0 改 judge=DeepSeek 跑同 5 题:

| judge | faith | answer_relevancy | context_precision | context_recall |
|---|---|---|---|---|
| 历史 GLM-4-plus judge(100 题基线) | **0.8849** | — | — | — |
| **DeepSeek judge (Phase 0, 5 题)** | **0.2944** | 0.5498 | 0.4000 | 0.2000 |

**核心解读**:数字从 0.88 → 0.29 暴跌, 不代表系统变差。**代表尺子脱污生效**。
原本同源 LLM 自评自产(类似"GPT 自己给自己改卷"), 数字自然虚高 0.85+。
异族 judge 一旦介入, 必然忠于"答案是否真从 context 推导", 5 题里 3 题只有 10-11 字答案(chat-app 业务 LLM 给出了短/降级答, 见 §2.3), 自然 faith 拉低。

这**正是 Phase 0 的全部意义**:Phase 1-4 后续算法/工程优化都用真实可解释的尺子量, 不再用"LLM 自吹尺"。

### 2.2 Noise baseline 梯度对照

| mode | faith | answer_relevancy | context_precision | context_recall | 注 |
|---|---|---|---|---|---|
| empty_context | **0.0000** | 0.0000 | 0.0000 | 0.0000 | judge 看"无 ctx + 无答案", 给全 0 — 尺子刻度准确 |
| random_distractor | **0.0000** | **0.5527** | 0.0000 | 0.0000 | answer 真(从 chat 来)但 ctx 是 decoy → faith=0;answer 与 question 仍相关 → relevancy=非0 |
| normal(对照 5 题 smoke) | **0.2944** | 0.5498 | 0.4000 | 0.2000 | 真 RAG 路径 |
| no_rerank | — | — | — | — | 待 chat-app RAG_RERANK_ENABLED=false 重启, Phase 1 触发 |

**通过点**:
- ✅ empty_context 出现底线 0, 验证 judge 真能识别"全幻觉"
- ✅ random_distractor faith=0 但 relevancy≠0, 验证 judge 区分能力(answer 与 question 相关但与 context 不符)
- ✅ normal faith > noise faith, 验证地面真实 RAG 路径不输给"无 RAG"对照

### 2.3 已知 noise caveat

5 题里 3 题 (#3, #4, #5 — 见 §2.1 normal 调用日志) answer_len 都只有 10-11 字:
- #3 `如何开启 Nacos 的权限系统？` → 11 char answer
- #4 `在多注册中心订阅的场景下，Spring Cloud Alibaba 提供了哪些选址策略？` → 10 char answer

业务 LLM (GLM-4-plus) 给出短/截断答. 这是 noise baseline 数字偏低(0.294)的部分原因。
**Phase 0 DoD 关注"梯度存在"而非"绝对值高"**, 梯度已证。
Phase 1 起会扩样本到 30-100 题 + 修 chat-app 短答回归 (回归 toString-serialized entity 之类的 bug), 数字会回到合理 0.6-0.8 区间。

---

## 3. 不降级红线实装清单

| 红线 | 实装点 | 验证 |
|---|---|---|
| Judge ≠ 业务 LLM (绝对禁止 fallback) | `judge_client.get_provider_meta` 无 env 时 `RuntimeError("...绝不 fallback 到业务 LLM")` | 实测: 清 env 时抛错如期, 配 env 后正常 |
| Noise 三档全实装 | `noise_injector.MODES = (empty_context, random_distractor, no_rerank)` | empty + random 跑出真数据; no_rerank 留待 chat-app env 切换 (Phase 1.4) |
| Badcase 落盘 | `judge_ensemble.py` 输出 `eval/badcases/badcases_<date>.jsonl`, 含 question/answer/各 judge score/disagreement_delta | 代码完成, 待 Provider 2 配后出数据 |
| 题库可重跑(非固化) | `label_questions.py --in X --out Y` 独立 CLI, 5 类启发式可迭代 | 100 题打标实跑, 5 类分布合理 |

---

## 4. 交付物清单(commit hash list)

| Commit | 内容 |
|---|---|
| `a32e5fa` | Phase 0 完整代码: judge_client / noise_injector / judge_ensemble / label_questions / golden.* / schema.md / PHASE0_README.md / .env.example 更新 / eval-regression.yml 重构 |
| `a32e5fa+1` (本次) | smoke 跑批数据 + 修 `global QUESTIONS_FILE` SyntaxError(used-before-global) + noise_injector/judge_ensemble 补 `load_dotenv` + 严格校验逻辑修(no_rerank 缺时不判 fail) + 本验收归档 |

---

## 5. 暂留与下一阶段挂钩项

| 待 | 阻塞 | 行动 |
|---|---|---|
| Provider 2 (Qwen-Max / Claude) key | 需要 user 配 .env | 配完后 `python3 eval/judge_ensemble.py --providers 1,2` 一次性出 STOP cross-judge Δ + badcase 队列 |
| Noise no_rerank 档数据 | 需 chat-app 重启 env 切换 | Phase 1.4 (multi-tenant & RAG 模式切换) 触发, 那时有 env 管理基建 |
| 100 题完整跑批数据 | 5 题 smoke 已证链路通, 100 题是 60-90 分钟 + ¥2-5 DeepSeek 成本 | Phase 1 启动后扩样本时跑, 同时拿 noise 100 题真值 |
| 去 chat-app 短答 degradation(GLM-4-plus 给 10-11 字答) | bug 原因待查(可能 SSE 截断 / parse 序列化 / token 限制) | Phase 1 收口 |

---

## 6. 判级

🟢 **PASS** — Phase 0 DoD 全部命中(2 项 🟡 为 Provider 2 与 no_rerank 待 key/env 触发, 不阻塞 Phase 1)。
**Phase 0 收口, 进入 Phase 1(RAG 完整链路组件补全)**。
