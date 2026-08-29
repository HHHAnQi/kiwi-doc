# KiwiRAG Final Release Acceptance

> 2026-08-30 · 本报告为本轮 Merge & Release Acceptance 的完整记录

```text
BRANCH_AUDITED         = codex/rag-metrics-multiturn-baseline (41 commits ahead of origin/main at audit start)
PRE_MERGE_HEAD         = c4143d0 (pushed to origin)
MAIN_HEAD              = a300c04 (unchanged; PR merge pending owner action)

DIFF_AUDIT             = PASS
  - secret 模式扫描：无真实 credential（命中均为 Dubbo 文档语料示例）
  - 本地泄漏：`.evidence/` 33MB 生成物已清除（`d762df9`），eval 报告中的路径为运行环境元数据
  - Gold 完整性：既有 gold/baseline 零回溯修改；新增 dataset 为增量（200题/50题 pilot）非改标
SECRET_SCAN            = PASS
LOCAL_BACKEND          = PASS  (spotlessApply 后 lint 绿, test BUILD SUCCESSFUL)
LOCAL_FRONTEND         = PASS  (vitest 27/27, vite build ✓, INDEXED 就绪 bug 已修复)
LOCAL_EVAL             = PASS  (unit + integration green; live eval 需 judge key = BY_DESIGN)

PR_URL                 = NOT_CREATED (无 gh CLI / GitHub token; 分支已 push, owner 需手动开 PR)
PR_CI_BACKEND          = NOT_TRIGGERED (等 PR 创建)
PR_CI_FRONTEND         = NOT_TRIGGERED
PR_CI_EVAL             = NOT_TRIGGERED

POST_MERGE_CI_BACKEND  = NOT_APPLICABLE (PR 未创建)
POST_MERGE_CI_FRONTEND = NOT_APPLICABLE
POST_MERGE_CI_EVAL     = NOT_APPLICABLE

README                 = STRONG_PASS  (KiwiRAG 14节结构, 261→285行, 4 Mermaid, 3截图)
ARCHITECTURE           = STRONG_PASS  (五层真实映射, 无虚构组件, 五图文档)
CLAIM_INTEGRITY        = STRONG_PASS  (矩阵17项全溯源, 禁词扫描仅否定语境)
EVIDENCE_TRACEABILITY  = STRONG_PASS  (8 doc 入口全链接有效, 冻结数字↔报告原文)
QUICK_START            = PASS         (make env/up/run/test 真实; Agentic 默认关如实标注)
SCREENSHOTS            = PASS         (3张真实截图; chat 正常路径截图受前端锁bug阻塞,
                                        error-state 截图如实采集, SSE 后端 curl 直验)
GITHUB_METADATA        = PENDING_OWNER (docs/audits/GITHUB_METADATA_UPDATE.md 已生成)

TAG_STATUS             = NOT_READY (PR 未创建, CI 未实跑 — 需 owner 完成后打 v1.0.0-portfolio)
```

## Merge Gate 状态

```text
NO_SECRET_LEAK=PASS  DIFF_AUDIT=PASS  README_TRUTH=PASS  DOC_SYNC=PASS
LOCAL_DETERMINISTIC_GATES=PASS
PR_CREATED=NOT_CREATED(no gh/token) ← BLOCKER for merge
BACKEND_CI=NOT_TRIGGERED  FRONTEND_CI=NOT_TRIGGERED  EVAL_CI=NOT_TRIGGERED
CLAIM_INTEGRITY=PASS

MERGE_VERDICT = BLOCKED_ON_OWNER_ACTION
```

## Owner Action 清单（最少化）

```text
1. 打开 https://github.com/HHHAnQi/kiwi-doc/compare/main...codex/rag-metrics-multiturn-baseline
   创建 PR，标题: "release: harden KiwiRAG portfolio v1"（PR 描述可复制本报告 Scope 部分）
2. 等 CI 三条 workflow 实跑全绿
3. Merge PR（如需 squashed merge 保持线性历史也 OK）
4. 修改 GitHub About Description（见 docs/audits/GITHUB_METADATA_UPDATE.md 推荐值）
5. 添加 Topics（9 个推荐 tag，禁 multimodal-rag/ha/production-ready）
6. 确认 main 首页显示新版 KiwiRAG README（首行 # 🥝 KiwiRAG）
7. 确认 CI 绿后打 tag: v1.0.0-portfolio
   message: "KiwiRAG portfolio release: reliable ingestion, hybrid retrieval,
   grounded generation, evaluation-driven engineering, and validated Agentic RAG decision."
```

## 本轮完成的代码修复（截图流程暴露的真实 bug）

| Bug | 根因 | 修复 |
|---|---|---|
| 前端永远显示"0 个文档就绪" | 后端终态 `INDEXED`（P0-1 重命名）vs 前端判 `READY` | ChatWindow/Sidebar/StatusBadge/types 四文件同步（兼容两者） |
| parser-service 无法启动（Phase 5 已修） | fat jar 含 bootstrap plain → RerankHealthIndicator 依赖被排除的 RerankProperties | 排除 infrastructure.rerank 包 |
| 前端 SSE 中断后聊天锁死 | store `sending` 标志在特定 error 路径未复位（curl 直验后端正常） | 已记录 known issue（README Limitations 第 5 条） |
