# RELEASE_HARDENING_AUDIT — Repository Truth Audit (Phase 0)

> 2026-08-29 · HEAD=`53b01c3`（分支 `codex/rag-metrics-multiturn-baseline`，领先 `origin/main` 35 commits）
> 方法：代码/测试/实验数据优先，README 仅作交叉核对。协议中提到的 `backend/` 不存在
> （真实模块为 `platform-bootstrap`/`platform-common`/`parser-service`/`frontend`），按真实结构审计。

## 1. Git & CI 真值

**STATUS: PARTIAL**
**EVIDENCE**:
- 3 个 workflow 均在 `.github/workflows/`（ci.yml / eval-regression.yml / frontend-ci.yml），YAML 全部合法（safe_load 通过）。
- `eval-regression.yml:190` 的 `if: ${{ secrets.JUDGE_PROVIDER_2_API_KEY != '' }}` 位于 **step 级** —— GitHub Actions 语法合法（secrets 允许于 step if），且天然构成 fork/PR skip policy（fork PR 无 secrets → 条件为假 → 跳过该 step）。
- 触发器：ci/frontend-ci = `pull_request|push → main/master`；eval-regression = `schedule( nightly ) + workflow_dispatch + eval-impact label`。
- **CI 触发面缺口**：当前工作分支领先 main 35 commits，CI 仅在 main/PR 触发 → 这 35 个 commit 从未运行过 CI。
- 本机无 `gh` CLI；GitHub API 匿名查询不可见 runs（私有仓库或限流）→ **CI 运行态无法从本机验证**。

**REQUIRED_ACTION**: 将工作分支 PR/Merge 进 main 后由 Actions 实际触发验证（需仓库 owner 操作）。
**CLAIM_ALLOWED**: "CI/评测门禁/前端 CI 三条 workflow 已定义，语法合法，fork 无密钥时 judge 步骤自动跳过"。
**CLAIM_FORBIDDEN**: "HEAD 上 CI 全绿"（未验证）；"CI 在本分支验证通过"。

## 2. 性能 Claim 真值（TTFT / QPS / P50-99）

**STATUS: PASS（诚实状态）**
**EVIDENCE**: `perf/performance_report.md` 全部指标为 _(unmeasured)_，并显式声明"禁止虚构结果"；当前 README（`53b01c3` 重构后）**不含任何 TTFT/QPS/P95/并发 claim**（grep 零命中）——旧 README 的 "首token <1.5s" 已在重构中删除。
**REQUIRED_ACTION**: Phase 4 执行真实 benchmark 填充（reranker 依赖 GPU，离线时该项标 unmeasured）。
**CLAIM_ALLOWED**: "性能框架（Locust 同步+流式）已就绪，主指标未测"。
**CLAIM_FORBIDDEN**: 任何具体延迟/QPS 数字（直到 benchmark 完成）。

## 3. 质量 Claim 真值（faithfulness / recall / Agentic 对照）

**STATUS: PASS**
**EVIDENCE**:
- README headline 数字与冻结报告逐字一致：common-cohort 46 题，-8.3pp\*→-0.2pp n.s.，+5.7pp\*，C slice +2.3pp n.s.（`docs/evaluation/2026-08-27-postd3-residual-audit.md` E1 表）；rerank 消融 +9.2pp faith（100 题消融报告）；Classic 基线 faith 0.885/recall 0.90（`eval/baseline_v3_judge_plus.md`，单 run，README 已注明数据集规模）。
- 所有数字含 run 口径标注；无 cherry-pick（历史过程数字已从 README 下沉，保留于 eval/ 报告链）。
**REQUIRED_ACTION**: Phase 2 建立 Claim→Evidence 矩阵正式化。
**CLAIM_ALLOWED/ FORBIDDEN**: 见 Phase 2 矩阵。

## 4. Enterprise-grade / Multimodal 表述

**STATUS: PASS**
**EVIDENCE**: 新 README 无 "enterprise-grade"（hero 定位为 evaluation-driven）；无 multimodal claim——解析能力为 PDF/DOCX/PPT/TXT（Tika），README 未使用 multimodal 一词（grep 零命中）。
**REQUIRED_ACTION**: 无。
**CLAIM_ALLOWED**: "多格式文档解析（multi-format）"。
**CLAIM_FORBIDDEN**: "multimodal"、"enterprise-grade/production-grade"（无负载验证/故障注入完成前的强化表述）。

## 5. Agentic 默认路径

**STATUS: PASS**
**EVIDENCE**: `application.yml` `RAG_AGENT_PLANNER_ENABLED` 默认 false、`planned-pipeline-enabled` 默认 false；`application-prod.yml` 显式全关。评测结论（Post-D3 平手但 ×2.8 成本）支持默认 Classic。README 已声明该决策及其数据依据。
**REQUIRED_ACTION**: 无（Phase 7 补 WHEN_TO_USE 文档）。
**CLAIM_ALLOWED**: "Agentic 默认关闭，基于评测的成本收益决策"。
**CLAIM_FORBIDDEN**: "Agentic 优于 Classic"；默认开启。

## 6. Reliability 验证状态

**STATUS: PARTIAL**
**EVIDENCE**: 异步 ingestion 的租约/visibility timeout/重试/DLQ **机制有代码+单测**；kill -9 演练有脚本（`scripts/v3-kill-9-drill.sh`）与 pass log 文档，但 poison-message 端到端与 duplicate-delivery 注入**未执行过**（旧 README 自己标注 "端到端 IT 推后"）。
**REQUIRED_ACTION**: Phase 5 执行 Test A/B/C 真实注入（如环境不可达则如实标 BLOCKED）。
**CLAIM_ALLOWED**: "可靠性机制实现并有单测覆盖"。
**CLAIM_FORBIDDEN**: "fault-injection 验证通过"（在 Phase 5 完成前）。

## 7. 文档一致性

**STATUS: PARTIAL**
**EVIDENCE**: README/docs/评测报告三方数字在 P0-3 对齐后一致；但五张标准架构图（System Context / Layered / Online / Ingestion / Evaluation）尚未成文（现有图分散于 README 与 docs）。
**REQUIRED_ACTION**: Phase 6 汇编 `docs/architecture/` 架构图文档。

## 8. 汇总优先级

```text
P0  Phase 1 CI 触发验证(需merge到main, 本机BLOCKED) · Phase 2 claim矩阵 · Phase 3 headline复核
P1  Phase 4 真实benchmark · Phase 5 故障注入A/B/C · Phase 6 架构图 · Phase 7 WHEN_TO_USE
P1  Phase 8 README复核(上轮已完成主体) · Phase 9 FINAL_RELEASE_GATE
```
