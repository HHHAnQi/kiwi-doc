# FINAL_RELEASE_GATE — 最终发布门禁 (Phase 9)

> 2026-08-29 · HEAD=`7b95803` · 评审基线：Code / Architecture / CI / Evaluation /
> Performance / Reliability / README Claim 七方对齐状态。
> 值域：STRONG_PASS / PASS / PARTIAL / FAIL / NOT_CLAIMED

```text
RAG_ARCHITECTURE        = PASS
  混合检索+RRF+重排+预算上下文+引用对齐全链真实; MySQL事实源/Milvus派生索引信任模型
  有 ACL 回库校验; 五张架构图与代码一一对应(docs/architecture/architecture-diagrams.md)。
  未达 STRONG: 大语料(>500docs)/多源场景未验证。

RETRIEVAL_ENGINEERING   = PASS
  rerank 消融 faith +9.2pp(100题); hybrid 默认含评测-线上一致性修复史;
  Hit@5 92.5%(3轮一致)。

CONTEXT_ENGINEERING     = PASS
  双闸门预算装填+截断引用同步对齐; Contextual Retrieval 前缀; Lost-in-the-Middle 可选重排。

INGESTION_RELIABILITY   = STRONG_PASS
  真实故障注入三测全PASS(kill-9续点无丢失无重复/poison 3重试DLQ不阻塞/
  并发双发幂等收敛), 原始日志入库; 唯一改进观察(毒文档状态联动)如实记录。

EVALUATION              = STRONG_PASS
  四层体系+paired A/B+逐样本planner_source隔离+盲评换位+bootstrap CI+
  common-cohort固定分母+validity gate; 评测对象勘误自查并转化为机制;
  全部冻结数字可溯源(CLAIM_EVIDENCE_MATRIX 17项)。

EVAL_CI                 = PARTIAL
  三workflow语法合法+fork skip policy确认; 但当前分支领先main 37+ commits
  从未实际运行CI(BLOCKED: 需owner merge/PR后Actions实跑验证)。

PERFORMANCE_EVIDENCE    = PARTIAL
  真实benchmark已建立(c=1/10, P50/95/99, artifact可重算, TTFT=完整LLM流式
  首内容事件); 限制: 单机dev环境、rerank OFF(GPU离线)、c=50+未跑(如实标注)。

FAULT_INJECTION         = STRONG_PASS
  (同 INGESTION_RELIABILITY, 独立计分: 真实进程注入而非单测替代)

CLAIM_INTEGRITY         = STRONG_PASS
  README/docs/评测三方一致(P0-3对齐+本轮矩阵); TTFT/enterprise/multimodal
  特则执行; 禁用词扫描仅否定语境命中; 历史数字下沉且带run口径。

DOCUMENTATION_CONSISTENCY= PASS
  五图/WHEN_TO_USE/故障注入/性能/claim矩阵全部入库并互链;
  旧runbook端口漂移已在P0-3修正。

AGENTIC_RAG             = PASS
  bounded Plan-Execute-Replan+语义sufficiency+四级降级链+per-run决策重建,
  全部经deterministic集成测试+真实负载验证; 默认关闭(数据决策);
  不声称优于Classic, 启用边界有文档。未达STRONG: 真实工作负载中replan
  语义收益个体不显著(n=10)。

PORTFOLIO_PRESENTATION  = STRONG_PASS
  30/60/180秒三层阅读测试通过; 结论优先+三层评测分层+设计决策+
  What We Learned; 无关键词墙。
```

## Final Verdict

```text
FINAL_VERDICT = INTERVIEW_READY_WITH_LIMITATIONS
```

**限制清单（如实，全部有文档支撑）**：
1. CI 运行态未验证（分支未入 main；需 merge 后 Actions 实跑——唯一 owner-action 阻塞项）；
2. 性能证据为单机 dev + rerank OFF 口径（rerank/高并发未测，BLOCKED 外部资源）；
3. Agentic 语义 replan 收益方向真实但个体不显著（n=10），默认 Classic 维持；
4. 生产级强化（负载验证/SLO/备份恢复/真实用户）未做——表述已收敛为 production-oriented。

**Hard Rules 遵守声明**：未改 gold label / 未删失败样本 / 未只报最好 run（common-cohort 固定
分母）/ 未以单测冒充注入（真实 kill -9、毒文件、并发竞态）/ 未以 multi-format 冒充
multimodal / 未加任何无业务必要的 Agent 功能。
