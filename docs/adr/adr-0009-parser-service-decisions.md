# ADR-0009: parser-service 拆分关键决策(MQ 选型 + 续点粒度 + DLQ)

- Status: Accepted
- Date: 2026-08-02
- 关联: ADR-0005(V3 服务拆分范围) / docs/v3/parser-service-spec.md

## Context

V3 第 1 周 parser-service spec(本次 commit `docs/v3/parser-service-spec.md` 完成)
设计过程中有 3 个关键决策点未在 ADR-0005 现成答案, 需各自落决策记录防止未来争议。

## Decision

### D1. MQ 选型 — **RocketMQ**

| 候选 | 优点 | 缺点 | 选 |
|---|---|---|---|
| RocketMQ | 原设计文档 V3 选型; 阿里生态; 重试+DLQ 原生支持 | Spring 社区 Kafka 生态更熟 | ✅ |
| Kafka | Spring 生态最熟; 高吞吐 | DLQ 不原生(需自己实现); 30 task/min 流量用 Kafka 过重 | ❌ |
| RabbitMQ | DLQ 路由灵活 | 吞吐弱; 重试语义不如 RocketMQ 直接 | ❌ |

V3 流量极小(<100 上传/小时), MQ 选型不卡瓶颈; 但原设计选 RocketMQ + 阿里生态对齐,
保持决策不动。V4 流量上来后可重评。

### D2. 续点粒度 — **chunk-level(V3), page-level(V3.5)**

V3 续点字段: `chunks_written` + `chunk_seq_offset`。
- 中断后重启 → 从 chunk_seq_offset 续 chunk seq, 不重切已 written 部分
- 不做 page-level(Tika Parser 不暴露 page 进度, 需 V3.5 接 PDFParser 改造)

**理由**: chunk-level 续点 = 单文档总切片耗时降 ~50%(已切部分不重做); page-level 续点要
改造解析器本身, 工时翻倍但收益边际递减。

### D3. DLQ 策略 — **RocketMQ 自带 DLQ topic**, 不另建 dead_letter_tasks 表

| 候选 | 优点 | 缺点 | 选 |
|---|---|---|---|
| RocketMQ DLQ topic | 极简配置; 已有 | 跨 broker 才能查; 表统计要 parse msg | ✅ |
| dead_letter_tasks 表 | SQL 查方便 | 重复落库; 维护负担 | ❌ |

V3 流量极低, rare DLQ 事件用 broker 自带 topic + atlas UI 看; V4 治理有审计需求时再落表。

## Alternatives Considered

详见 3 个决策各自的对照表。

## Consequences

**正面**:
+ V3 第 1 周 spec 锁定可动代码, 3 个 Open Question 不再悬
+ 与原设计文档 V3 选型(RocketMQ)一致, 历史 review 链不断
+ 续点粒度合理不过度工程

**负面**:
- page-level 续点延后 V3.5(若客户/演示时大 PDF 中断, 续点覆盖不完整)
- DLQ 不落表 → V4 DevOps 必须接 RocketMQ console

## Revisit

V3 第 1 周末(commit 3 验收):
- 若 kill-9 重启续点实测耗时 > 单解析 50%(没起到续点价值) → 升 V3.5 page-level
- V4 治理版若需复杂审计 → 把 DLQ 落表
