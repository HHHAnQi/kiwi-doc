# CLAIM_EVIDENCE_MATRIX — 全仓对外 Claim 证据矩阵 (Phase 2)

> 2026-08-29 · 范围：README.md（公开落地页）全部量化/能力 claim + 高频被引用的 docs claim。
> Verdict 定义：VERIFIED（多run/逐样本可复算）· SUPPORTED_WITH_LIMITATION（单run/小样本/有口径限制）
> · EXPERIMENTAL（探索性结论）· REMOVE（应删除）。

| # | Claim | Metric Definition | Dataset | Runs | Raw Evidence | Reproducible | Verdict |
|---|---|---|---|---|---|---|---|
| 1 | Post-fix Agentic vs Classic = **-0.2pp (n.s.)** | 配对 correctness 差值, 双judge绝对分, bootstrap 95%CI | common cohort 46/50 题（pilot50, sha256 a6d55294） | 1 run/臂（run内配对+Classic跨run噪声桥对照） | `eval/agentic/reports/pilot50_postd3/paired_ab_product_run1.json` + `docs/evaluation/2026-08-27-postd3-residual-audit.md` E1表 | 重跑同spec可复算（spec冻结于 3d3120b） | SUPPORTED_WITH_LIMITATION（单run；CI已含不确定性） |
| 2 | Pre-fix Agentic vs Classic = **-8.3pp\*** | 同上（Pre-D3=同代码去D1-D3） | 同上 46 题 | 同上 | 同上 E1表（CI[-16.7,-1.5]） | 同上 | SUPPORTED_WITH_LIMITATION |
| 3 | 修复效应 Post−Pre = **+5.7pp\***（CI[+0.9,+11.7]） | Agentic跨run配对差 | 46 题 | 2 arms×1 run | E1表 | 同上 | SUPPORTED_WITH_LIMITATION |
| 4 | Multi-hop(C) slice **+2.3pp n.s.**（Pre −10.0pp\*） | 分slice配对差 | C slice n=22 | 同上 | E1表 | 同上 | SUPPORTED_WITH_LIMITATION |
| 5 | 语义 replan 触发率 **21%（10/48）**，触发子集 +3pp | replan_count>0 逐样本（planner_version核验MODEL 48/50） | pilot50 post-D3 | 1 run | run1.json 逐样本 + Prometheus `replan_total{ALLOWED}=11` 权威计数 | 可复算 | SUPPORTED_WITH_LIMITATION（n=10 子集不显著，README已注明） |
| 6 | Agentic 成本 **~2.8× 延迟 / 3.4 LLM calls/run** | e2e latency 均值比 / (planner62+suff62+composer49)/51 | pilot50 双臂 | 1 run | runner latency 字段 + `/actuator/prometheus` 差值（159cf5d） | 可复算 | VERIFIED（该run内精确计数） |
| 7 | Sufficiency 语义判定 human agreement **42%→96%**, false-sufficient 100%→4% | 24对独立holdout（6类）人工标签 vs 管线判定, 真实LLM(glm-4-plus) | holdout 24对（与dev集不同题域） | 1遍(每对1判定) | D3修复 commit 62decb1 报告 + 探针测试 `SufficiencySemanticProbeTest`（dev集20对构造性断言） | holdout脚本化于审计记录 | SUPPORTED_WITH_LIMITATION |
| 8 | Classic 基线 **faith 0.885 / recall 0.90** | RAGAS faithfulness/recall, 异族judge | 80题冻结集(30题extractive GT子集), 100 docs | **单 run**（P0 run final） | `eval/baseline_v3_judge_plus.md` | `eval/ragas_pipeline.py` 可重跑 | SUPPORTED_WITH_LIMITATION（单run；README已标注数据集规模） |
| 9 | rerank 消融 **faith +9.2pp / recall +7.5pp** | 100题消融, 其余配置不变 | 100题消融集 | 1 run/臂 | 消融报告（docs/evaluation/） | 脚本可重跑 | SUPPORTED_WITH_LIMITATION |
| 10 | 拒答率 16%→6%（README highlights 未引, 保留于docs） | 拒答分离指标 | 冻结集 | 多轮历史 | 修复闭环表（下沉docs） | — | SUPPORTED_WITH_LIMITATION |
| 11 | **评测对象勘误**（第一轮200×3测的是Rule Planner） | 配置考古 + planner_source机制 | — | — | P0-2 spec 背景段 + git log 9eed1a1 前 application.yml | — | VERIFIED |
| 12 | Planner 四级降级链零静默降级 | 逐样本 planner_version + 指标 | pilot50 | 1 run | `planner_source_counts={MODEL:48,NO_RUN:2}`，0 rule/classic fallback | 单测9场景+集成T7 | VERIFIED |
| 13 | decision_summary 五类可区分 | per-run DB字段 | 强制replan集成测试T1-T5 + 本地实测 | — | commit 3bec986 + 实测头/API | 单测+实测 | VERIFIED |
| 14 | ~~TTFT < 1.5s~~ / QPS / P95 | — | — | — | **README 无此 claim**（旧版已删）；`perf/performance_report.md` 全部 unmeasured | Phase 4 benchmark 后建立 | （无claim，无需REMOVE） |
| 15 | ~~enterprise-grade~~ / ~~multimodal~~ | — | — | — | README 无（grep 零命中）；解析为 multi-format(PDF/DOCX/PPT/TXT) | — | （无claim） |
| 16 | kill -9 ingestion 韧性 | 故障注入 | 真实RMQ+parser-service | 演练1次（有pass log） | `docs/v3/kill-9-drill-pass-log.md`；poison/duplicate 注入未做（Phase 5） | 脚本在 | SUPPORTED_WITH_LIMITATION → Phase 5 后升级 |
| 17 | CI 三 workflow + eval 回归门禁(-3pp) | workflow定义 | 30题 | nightly设计 | `.github/workflows/*`（语法合法） | 需merge main 实跑 | SUPPORTED_WITH_LIMITATION（运行态BLOCKED） |

## 规则执行记录

- **TTFT 特则**：perf 报告确为全 unmeasured → README 已无 <1.5s（上轮重构删除），无需动作；
  Phase 4 用真实 benchmark 建立后允许恢复为实测表述。
- **Enterprise-grade 特则**：负载/故障注入/SLO/备份/真实用户验证均未完成 → 全仓禁用该词
  （当前已无使用）。允许 "production-oriented"（prod profile/runbook 存在）。
- **Multimodal 特则**：仅 Tika 多格式解析 → 只允许 multi-format（当前已如此表述）。
- **Cherry-pick 特则**：README 只展示 common-cohort 冻结结果；历史最好单次（如 0.497 等）
  已下沉 docs 且带 run 口径。
