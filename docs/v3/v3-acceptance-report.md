# V3 验收报告

**报告起草日**: 2026-08-02
**对应 spec**: docs/v3/parser-service-spec.md §9 / ADR-0010
**当前 git HEAD**: f4e69ff (V3-W1 收工 README)
**报告状态**: 🟡 骨架, 数字占位待 P0 微评估填充后转 Accepted

---

## 0. 报告读法

本报告随 V3 进度迭代, **不是 V3 完工一次性写就**。每个 Section 后标:
- ✅ = 已确定(代码 / 测试 / 真数字支撑)
- 🟡 = 占位(P0 微评估 / 实跑演练未做,数字待填)
- ❌ = 留 V4 / 已主动砍掉

---

## 1. V3 范围回顾(ADR-0010 修正版)

| W | 原 V3 spec 内容 | 最终落点 |
|---|---|---|
| W0 | corpus 50→100 docs | ✅ 完成 |
| W0 | curated 30 题 baseline 真数字 | 🟡 数字已出但已知偏低(rerank OFF + 旧 curate), 真值见 §4 |
| W1 | LLM SSE 流式 chat(首 token <1.5s) | ✅ commit `eebc6c8` |
| W1-2 | parser-service 拆 + 中断恢复 + kill -9 演练 | ✅ commit `2e08915` `3941013` `5029f3b`(代码), 🟡 演练实跑日志待 mac 窗口 |
| W3 | Langfuse + 自动 RAGAS CI | 🟡 进行中(本报告之后下一个工作) |
| W3-4 | docker-compose 全栈 + Locust 100 并发 | 🟡 compose 落地(含 RocketMQ), Locust 降级 V4 |
| W4 | V3 验收报告(本文件) | 🟡 起草中 |

---

## 2. DoD 验收对照(spec §9)

| DoD | 代码 | 验证 | 状态 |
|---|---|---|---|
| DoD-1 kill -9 优雅降级 | ✅ ParseWorker + VisibilityTimeoutScheduler + lease_until | `scripts/v3-kill-9-drill.sh` + runbook | 🟡 代码完成, 实跑 PASS log 待 mac 窗口 |
| DoD-2 重试续解析 + DLQ | 🟡 ParseTaskService.markFailed(retry_count++) + RocketMQ redelivery → `%DLQ%` topic | unit (ParseTaskServiceTest 11 cases) | 🟡 端到端集成测试待补(spec §7.2 poison message 场景) |
| DoD-3 p95 < 2s | ❌ | — | ❌ Locust 100 并发压测推 V4 (0 用户场景演不出 HP) |
| DoD-4 中断续点 | ✅ ParseWorker.checkpointProgress 每 10 chunks flush | `scripts/v3-kill-9-drill.sh` | 🟡 同 DoD-1, 实跑 PASS log 待 mac 窗口 |
| DoD-5 trace(Langfuse 接入) | ❌ chat_traces 表已有, Langfuse SDK 待接 | — | 🟡 V3-W3 下一步 |
| DoD-6 灰度降级演练(sync↔async 切换) | ✅ @ConditionalOnProperty(rag.parser.mode) + DocumentUploadService 零改动端口切换 | sync 模式回归测试 pass | 🟡 实跑切换演练待加到 drill 脚本 |

---

## 3. 数据流时序图(spec §6.1 拓扑落地)

### 3.1 V3-W1 末服务拓扑

```
                              ┌──────────┐
                              │  MySQL   │ ← 共享(同步 schema, V1-V5 migration)
                              └──┬───────┘
              ┌───────────┐      │      ┌──────────┐
              │ Chat App  │      │      │  MinIO   │
              │ (Bootsrap)│      │      └──┬───────┘
              │  :8080    │      │         │
              │ ─ upload  ├──────┘         │
              │ ─ chat    │      ┌─────────┴──┐
              │ ─ feedback│      │  Milvus    │
              │ ─ chunk   ├─────►│  :19530    │
              │   query   │      └────────────┘
              └─────┬─────┘
                    │ produce
                    │ parse-task-submit
                    ▼
              ┌────────────┐
              │ RocketMQ   │ ← V3-W1 新增中间件
              │ :9876      │
              └─────┬──────┘
                    │ consume(RocketMQMessageListener)
                    ▼
              ┌──────────────┐
              │parser-service│ ← V3-W1 新增独立进程
              │ :8093        │
              │ ─ ParseWorker│ → Tika + chunk + embed + Milvus
              │ ─ Consumer   │ → lease/续点/markParsed
              │ ─ Scheduler  │ → 心跳回收 zombie RUNNING
              └──────────────┘

              ┌────────────────┐
              │ Autodl GPU     │
              │ (reranker)     │ ← BGE-Reranker-v2-m3, SSH 隧道暴露 8084
              └────────────────┘
```

### 3.2 Happy path 时序(spec §4.1 落地)

```
Client       chat-app   RocketMQ   parser-svc  MySQL    MinIO   Milvus
  │            │            │          │         │        │       │
  │ POST /docs │            │          │         │        │       │
  │──────────► │            │          │         │        │       │
  │            │ validate   │          │         │        │       │
  │            │ hash SHA-256          │         │        │       │
  │            │ INSERT documents UPLOADED        │        │       │
  │            │ ────────────────────────────────►│        │       │
  │            │ MinIO upload raw                  │        │       │
  │            │ ────────────────────────────────────────►  │       │
  │            │ INSERT parse_tasks PENDING         │        │       │
  │            │ ────────────────────────────────► │         │       │
  │            │ producer.syncSend(parse-task-submit)        │      │
  │            │ ──────────► │            │         │        │       │
  │ 202        │             │            │         │        │       │
  │ ◄──────────│             │            │         │        │       │
  │ (上传 RTT <3s, 同步 5-15s 时代过去)                                          │
  │                          │ @RocketMQListener           │        │
  │                          │ ─────────►  │                │       │
  │                          │             │ UPDATE RUNNING leased_by=host1:pid
  │                          │             │ ──────────────►│       │
  │                          │             │ download raw ────────► │
  │                          │             │ Tika + chunk          │
  │                          │             │ (每 10 chunks UPDATE chunks_written)
  │                          │             │ embed → BGE-M3 (HTTP) │
  │                          │             │ INSERT chunks ───────►│
  │                          │             │ upsert Milvus ──────────────►
  │                          │             │ UPDATE PARSED + doc.markReady     │
  │                          │ ack         │ ──────────────────►│              │
  │                          │ ◄───────────│                                                   │
```

### 3.3 kill -9 故障链路(spec §4.2 落地)

```
T0: parser-svc 收到 msg, UPDATE RUNNING leased_by=host1 visible_at=now+5min
T1: parser-svc kill -9, msg 没 ack → broker 红重投
T2: VisibilityTimeoutScheduler 每 30s 扫 status='RUNNING' AND visible_at<now → 不命中(还在 5min 内)
T3+5min: visible_at 过期, 下一个 30s 周期命中 → UPDATE PENDING
T4: parser-svc 重启(或其他 worker) 拉到重投 msg(or PullMetrics), lease 转 RUNNING, leased_by=host2
T5: ParseWorker 读 chunks_written / chunk_seq_offset 续点(spec §3.1 续点字段)
T6: 跑完 → markParsed → doc.markReady
```

---

## 4. 评测真值表(P0 微评估后填)

> ⚠️ 本节全部数字待 2026-08-XX 跑完 P0 后填回。
> 当前 README 上标注的 faith 0.5950 / recall 0.4316 是过程数字,不该作为 V3 验收数字。
> 填入原则: ≥3 次 RAGAS 跑 mean ± std(ADR-0008 D2 噪声定标), 同 judge(GLM-4-plus + thinking disabled), 同 corpus(预计 150 docs)。

### 4.1 最终 baseline(填表格式)

| 指标 | mean | std | 备注 |
|---|---|---|---|
| faithfulness | TBD | TBD | judge = TBD, corpus = TBD docs |
| answer_relevancy | TBD | TBD | |
| context_precision | TBD | TBD | |
| context_recall | TBD | TBD | |

### 4.2 关键 A/B 对照(决策依据)

| 假设 | A 组 | B 组 | 结论 | 决策 |
|---|---|---|---|---|
| Parent-Child 是否超越 flat | flat+rerank | parent-child+rerank | TBD | TBD |
| Rerank 净增是否 >5pp | rerank OFF | rerank ON | TBD(预期 +5-7pp) | TBD |
| corpus 扩 150→200 杠杆是否递减 | 150 docs recall | 200 docs recall | TBD | TBD |

---

## 5. 工程硬资产清单(纯代码侧已落地)

> ✅ V3 代码资产全部 commit + push 上 GitHub (origin/main HEAD `f4e69ff`)

### 5.1 parser-service(V3-W1 第 1 周)

| 模块 | 文件 | 行数 | 测试 |
|---|---|---|---|
| 共享层下沉 | platform-common: ParseTask / ParseTaskStatus / ParseTaskRepository / ChunkingService / MarkdownStructurer / TextCleaner / ChunkingProperties | ~900 | (借 chat-app_chunking 测试) |
| 状态机守护 | parser-service: ParseTaskService | 200 | ParseTaskServiceTest 11 cases |
| Worker | parser-service: ParseWorker(Tika + chunk + embed + Milvus, 每 10 chunks checkpoint) | 447 | (端到端测试待补) |
| MQ 消费 | parser-service: ParseTaskConsumer(@RocketMQMessageListener + lease + 状态迁终) | 215 | (集成测试待补) |
| 心跳 job | parser-service: VisibilityTimeoutScheduler(@Scheduled fixedDelayString=30s) | 60 | (实跑待补) |
| chat-app MQ producer | platform-bootstrap: ParseTaskProducer + ParseTaskSubmitMessage + AsyncParsingTrigger + JpaParseTaskRepository + ParseTaskEntity + ParseTaskJpaRepository + ParseTaskMapper | ~600 | (借 chat-app_upload 测试) |

### 5.2 评测体系

| 资产 | 文件 | 状态 |
|---|---|---|
| 30 题 curated jsonl | eval/questions.curated.jsonl | ✅ |
| RAGAS 评测脚本 | eval/ragas_pipeline.py | ✅ |
| 评估对照脚本 | eval/compare_baseline.py | ✅ |
| Baseline 数字 | eval/baseline_v3_judge_plus.md | 🟡 过程数字, 待 P0 重测 |
| CI 门禁 workflow | .github/workflows/eval-regression.yml | 🟡 W3 落地 |

### 5.3 故障韧性资产

| 资产 | 路径 | 状态 |
|---|---|---|
| kill -9 演练脚本 | scripts/v3-kill-9-drill.sh (415 行) | ✅ 代码, 🟡 实跑日志待补 |
| Runbook | docs/v3/kill-9-drill-runbook.md | ✅ |
| RocketMQ broker | deploy/docker-compose.yml 内 rmqnamesrv + rmqbroker | ✅ |

---

## 6. 已知问题(诚实标注)

| # | 问题 | 影响 | 解决路径 |
|---|---|---|---|
| 1 | 当前 baseline 数字 faith 0.60 / recall 0.43 在 production-grade RAG 中属不合格 | demo 数字难看 | P0 微评估(1-2h)+ corpus 扩 150 docs |
| 2 | 30 题 curated ground truth 是基于旧 50 docs corpus 合成, 与 100 docs corpus 不匹配 | recall 被人为拉低 | 重新 curate 30 题(P0.1) |
| 3 | V3 P2 baseline rerank OFF | faith +5-7pp 没拿到 | P0.2 rerank ON 重跑 |
| 4 | Judge LLM 从 GLM-4-flash 切到 plus 跨 baseline 不可比 | 数据历史对照混乱 | P0.3 全 baseline 用统一 judge |
| 5 | 真实流量 = 0 | 演 HPA / Locust 100 并发无意义 | V4 + 流量来时启 |
| 6 | V3-W1 没有 corpus ≥ 200 docs 的真值 | parent-child 是否真好不确定 | V3-W2/W3 P0 完成后看 |
| 7 | Locust 100 并发压测未做 | P95 / 系统容量无真值 | V4(0 用户场景意义低) + 真流量来时 |

---

## 7. V3 进 V4 启动门槛判据

**任一条件命中即触发 V4 立项**:

| # | 门槛 | 触发条件 | V4 大致内容 |
|---|---|---|---|
| 1 | 评估门槛 | V3 最终 baseline faithfulness ≥ 0.75 AND context_recall ≥ 0.65 | V4 进 RAG 二阶调优(hyde / query rewrite / 重排换模型) |
| 2 | 性能门槛 | V3 真实流量 P95 > 2s 持续 7 天 | V4 进 HPA on k3s + Semantic Cache |
| 3 | 业务门槛 | 真实流量周 upload ≥ 200 docs OR 周活跃用户 ≥ 10 | V4 进多租户 + 用户体系 + 审计 |
| 4 | 运维门槛 | DLQ 月事件 ≥ 10 OR 月故障 ≥ 1 | V4 进 DLQ 治理审计表 + page-level PDF 续点 |
| 5 | 客户门槛 | 客户/演示要求 K8s 演示 OR 多租户 OR 多 LLM | V4 进 k3s / 多租户 / llm-gateway 拆分 |

### 7.1 哪些不该立刻进 V4(避免 ROI 陷阱)

- ❌ **K8s / k3s**: ADR-0007 已 Superseded, 真流量没来演不出 HPA 价值
- ❌ **rag-service / llm-gateway 拆分**: ADR-0005 部分 Superseded, 0 用户场景空壳拆分
- ❌ **Semantic Cache**: 100 并发压测无 cache 都能过(ADR-0010 已论证)
- ❌ **PDF page-level 续点**: Tika Parser 不暴露 page 进度, 改造工时翻倍但收益边际递减(ADR-0009 D2)

### 7.2 V3 → V4 推荐主线(基于 V3 验收真实数据决定)

| V3 验收情况 | V4 推荐主线 |
|---|---|
| faith ≥ 0.80 / recall ≥ 0.70 → RAG 质量已合格 | V4 主线 = 流量/部署(k3s + HPA + 监控) |
| faith ∈ [0.70, 0.80) / recall ∈ [0.60, 0.70) → 临界 | V4 主线 = RAG 二阶调优(hyde / Reranker 换模型) |
| faith < 0.70 OR recall < 0.60 → 不合格 | V4 主线 = 切片方向重审 + corpus 扩到 500+ |

---

## 8. 当前 V3 完成度(V3 整体进度, 实时更新)

```
V3 完成度: ≈ 60%(ADR-0010 主线 7 项里 4 项完成 + 部分完成)

✅ 完成(代码 + push):
  W0.1  corpus 100 docs
  W0.2  baseline 真数字(但过程性, 待 P0 重测)
  W1    SSE 流式 chat
  W1-2  parser-service 拆 + DoD-1/2/4 代码

🟡 部分完成:
  W3.1  Langfuse SDK 接入(0 进度, 进行下一步)
  W3.2  RAGAS CI 门禁(0 进度)
  W4    本报告(骨架完成)

❌ 已主动砍(不出现在完成度计算):
  W3-4 Locust 100 并发压测
  DoD-3 p95 <2s
  K8s / k3s(ADR-0010)
  rag-service / llm-gateway 拆(ADR-0010)
```

---

## 9. 下一步动作(本报告持有人 todo)

按价值 ROI 排:

1. ✅ 本报告起草完成
2. 🔴 P0 微评估(待 mac 空闲 1-2h): 重 curate 30 题 + rerank ON 重跑 + 填 §4
3. 🟠 Langfuse SDK 接入 chat 链路(W3, 不依赖环境可立刻做)
4. 🟠 ADR-0008 RAGAS 落 GitHub Actions CI 门禁(W3, 不依赖环境可立刻做)
5. 🟡 DoD-2 集成测试(spec §7.2 poison message → DLQ)
6. 🟡 kill -9 演练实跑日志(待 mac 空闲, 入 §5.3 选表)

---

## 修订记录

| 日期 | 修订 | 作者 |
|---|---|---|
| 2026-08-02 | 报告起草, 完成 §3/§5/§7/§8; §4 占位 | (架构师视角) |
| TBD | §4 填表(P0 微评估完成) | TBD |
| TBD | §2 DoD-1/DoD-4 实跑 PASS log 入选 | TBD |
| TBD | 报告转 Accepted(V3 整体验收完成) | TBD |
