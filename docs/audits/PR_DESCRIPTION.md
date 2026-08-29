# PR Description — release: finalize KiwiRAG portfolio v1

> 使用方法：打开
> `https://github.com/HHHAnQi/KiwiRAG/compare/main...codex/rag-metrics-multiturn-baseline`
> 点击 "Create pull request"，将本文件全文粘贴为 PR description。

---

## Scope

- Portfolio README redesign（🥝 KiwiRAG 品牌定位、14 节渐进结构、3 张真实截图、4 Mermaid 图）
- Architecture / docs 同步（五张架构图、ADR ×15、WHEN_TO_USE_AGENTIC_RAG）
- Eval evidence 整理（CLAIM_EVIDENCE_MATRIX、common-cohort 审计、Post-D3 pilot 冻结报告）
- Reliability validation（FAULT_INJECTION_REPORT：kill-9 / poison / duplicate 三测全 PASS）
- CI workflow 修复（eval-regression `steps.if` 中 `secrets.*` → secret-checkpoint env 映射，
  修复 fork PR 下的 `Unrecognized named-value: 'secrets'`）
- Real performance benchmark（`perf/benchmark/bench.py` + P50/P95/P99 artifact）
- Claim cleanup（README 中删除所有未经验证的性能/enterprise/multimodal 表述）
- 前端 bug 修复（`INDEXED` 终态 vs `READY` 就绪判定同步）
- Parser service 启动回归修复（`RerankHealthIndicator` 依赖排除）

**NO_NEW_PRODUCT_FEATURE**

## Major verified changes

1. **Sufficiency 语义修复**（`62decb1`）：Rule 只做确定性判定（NO_EVIDENCE/entity mismatch/
   version conflict），语义充分性归 LLM judge → holdout false_sufficient 100%→4%，
   human agreement 42%→96%
2. **Post-D3 真实负载验证**（`159cf5d`）：46 题共同 cohort，Agentic vs Classic 从 -8.3pp\*
   收敛到 -0.2pp n.s.（统计平手），多跳 slice 首次名义反超 +2.3pp
3. **Replan 路径双断路修复**（`9e33004`）：D1 step-id 命名空间冲突 + D2 attempted-query
   上下文缺失 → deterministic forced-replan integration test 全链验证
4. **Observability 最小修复**（`3bec986`）：per-run `decision_summary`（不被终态覆盖）+
   sync/SSE correlation parity（X-Agent-* 响应头 ⇄ DoneEvent）+ 失败路径真实 runId
5. **故障注入三测全 PASS**（`631c33e`）：kill -9 worker 续点零丢失零重复；毒消息 3/3 重试
   DLQ 不阻塞；并发双投幂等收敛单一文档
6. **真实性能 benchmark**（`0063e33`）：c=1 E2E P50 1029ms / TTFT(SSE 首内容) 688ms；
   c=10 Hikari 池耗尽如实记录
7. **CI 修复**（本 PR 最新 commit）：eval-regression secrets-if 合法化 + deterministic
   eval 始终跑 + live judge 缺 secret 时显式 warning skip

## Known Limitations

- **Agentic RAG 默认关闭**——修复后统计平手，但 ×2.8 延迟 / 3.4 LLM calls 不抵成本
- **real-user validation scope**——extractive GT 为 LLM 生成题集，真实流量校准为 V4
- **multi-format, not multimodal**——PDF/DOCX/PPT/TXT，无 image extraction/OCR/VLM
- **live judge eval 依赖 repository secrets**——fork PR / 无 secret 时 judge 步骤 skip
  （deterministic Phase 0.1 始终跑）
- **deployment scale**——单机 dev 验证，无 K8s/负载测试
- **前端 SSE 中断后聊天锁死**（known issue，后端 SSE 正常）

## Merge instructions

- 三条 CI workflow 实跑确认后合并（backend / frontend / eval-regression）
- 合并后按 `docs/audits/GITHUB_METADATA_UPDATE.md` 更新 About Description 与 Topics
- 确认 main 首页首行为 `# 🥝 KiwiRAG` + 3 张截图 Demo 节
- 更新 README Limitations 第 1 条（CI 运行态）为真实状态
- CI 绿后打 tag `v1.0.0-portfolio`
