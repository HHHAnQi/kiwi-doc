# Router Evaluation Dataset（PR-3.0 / EMS-PR3）

> 用于 PR-3 RuleBasedTaskRouter 的路由准确率评测。**版本化、人工确认 gold 标签**。

## 数据集约定

- 文件: `router_cases.jsonl`，共 **100 条**
- 每条字段:

```json
{
  "caseId": "router-001",
  "question": "Spring Boot 启动流程是什么",
  "intent": "FACT",
  "expectedStrategy": "CLASSIC_RAG",
  "entities": [],
  "reason": "plain_concept_fact"
}
```

- `intent` 与 `expectedStrategy` 必须互斥对齐 PR-3 §1 锁定的映射表:

| Intent | ExpectedStrategy |
| --- | --- |
| FACT | CLASSIC_RAG |
| ENTITY_LOOKUP | TARGETED_RAG |
| NUMERIC_OR_VERSION | TARGETED_RAG |
| COMPARISON | FIXED_WORKFLOW |
| MULTI_HOP | FIXED_WORKFLOW |
| SUMMARY | CLASSIC_RAG |
| UNANSWERABLE | REFUSE |

## 分布

| Intent | 条数 |
| --- | ---: |
| FACT | 20 |
| ENTITY_LOOKUP | 10 |
| NUMERIC_OR_VERSION | 15 |
| COMPARISON | 20 |
| MULTI_HOP | 15 |
| SUMMARY | 10 |
| UNANSWERABLE | 10 |
| **Total** | **100** |

## 数据来源与 Gold 标签人工确认

按 EMS-PR3 与禁止事项第 11 条规则，**禁止 LLM 自动生成 Gold 后未经人工审核直接作为正式测试集**。

本数据集来源:

1. **第一来源**: 项目知识库涉及的 Spring Cloud Alibaba 生态（Dubbo / Nacos / Seata / RocketMQ / Sentinel / Gateway / OpenFeign 等），按真实 chat 历史与 badcase 模式人工构造典型问法
2. **第二来源**: 按 PR-3 §1 规则集手工标注 `intent` / `expectedStrategy` / `reasonCode`
3. **未使用 LLM 自动生成标签**: 本批 100 条全部是人工撰写 + 人工标注

后续扩充时允许 LLM 候选生成 + 必须人工 Review 才能 commit。

## 版本

- v1.0 (PR-3.0): 100 条基线集
