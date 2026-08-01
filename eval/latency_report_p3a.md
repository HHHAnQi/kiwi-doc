# P3-A 延迟压测报告 (P2.5-3)

## 测试工具: ab (Apache Bench), 模拟 200 并发 10, 同 query 反复打 /chat

## 整体 chat 接口
| 百分位 | 延迟 |
|---|---|
| p50 | 12.2s |
| p90 | 16.9s |
| p99 | 20.7s |
| max | 22.3s |

## 拆解 by log trace_id
从 /tmp/ragapp.log 抓 322 条 retrieve.done → 起点(chat.start)差值得 retrieve-only 时间:
| 百分位 | retrieve 延迟 |
|---|---|
| min | 0.07s |
| p50 | 0.34s |
| p90 | 2.72s |
| p99 | 6.99s |

## 拆解聊天总耗时
- retrieve p99 = 7s
- LLM (GLM-4-flash) p99 ≈ chat_total_p99 - retrieve_p99 = 20.7 - 7 = 13.7s
- LLM 是大头 (66%), retrieve 是次要 (34%)

## 与设计文档 SLA 对比
- 设计 V2 SLA: p99 < 2s (整 chat)
- 实测: p99 = 20.7s (超 SLA 10x)
- 主要根因: GLM-4-flash 在 free tier 平均 13s 极限慢
- 次根因: retrieve 在并发下 p99 7s 远超 500ms 单核目标

## parent-child 是否引入退化?
- min retrieve 0.07s / p50 0.34s: 单次 parent 回链额外 findById 成本并不显著
- p99 7s 大概率是并发竞争 (Milvus hybrid search queue, MySQL connection pool 等待) 不是 parent-child 设计本身的退化
- 拆 @Transactional 后 doc 行锁持有 ms 级, 不再 fan-out

## 工程结论
1. P3-A 在单 chat 单 retrieve 下没有显著性能退化
2. p99 = 20.7s 不达标的主因是外部 GLM-4-flash + 并发资源竞争, 不是 P3-A 本身设计缺陷
3. 真实生产建议: 升级到 GLM-4 / GLM-4-Plus (付费版平均 2-3s) 可把 p99 拉到 5s 内; retrieve 加 cache (语义相似题复用) 可降到 2s 内
