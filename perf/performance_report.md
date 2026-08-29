# RAG 性能测试报告（Phase 4 真实 benchmark 重写版）

- **生成时间**: 2026-08-29 · **git commit**: `554de74`（工作分支）
- **环境**: 本地 MacBook (arm) + Docker 中间件（MySQL/Redis/Milvus/MinIO）；chat-app dev profile
- **检索模式**: **hybrid（rerank OFF, `RAG_RERANK_ENABLED=false`，GPU 服务器离线）**
- **LLM**: `glm-4-plus` @ Zhipu `open.bigmodel.cn`（同步+SSE 同一模型）
- **语料**: 165 docs / 3076 chunks（已索引）；**查询集**: 冻结 pilot 数据集 8 题轮换
- **工具**: `perf/benchmark/bench.py`（原始 per-request 样本存 `perf/benchmark/result_c*.json`，可重算）
- **TTFT 定义**: SSE 流式**首个含内容的流事件**（完整 LLM TTFT，非 API acknowledgement、非检索延迟）

> 任务要求: 禁止虚构结果。未跑场景或没出数据的 cell 一律标 _(unmeasured)_。

## 1. 主指标（实测）

| 场景 | 并发 | N | QPS | P50 (ms) | P95 (ms) | P99 (ms) | 失败 |
|---|---:|---:|---:|---:|---:|---:|---:|
| retrieve（无 LLM, rerank OFF） | 1 | 30 | 1.52 | 275 | 1,497 | 7,148 | 0 |
| retrieve（无 LLM, rerank OFF） | 10 | 60 | 1.98 | 3,255 | 10,112 | 10,339 | 0 |
| LLM TTFT（SSE 首内容事件） | 1 | 30 | — | **688** | 3,824 | 18,539 | 0 |
| LLM TTFT（SSE 首内容事件） | 10 | 60 | — | 4,293 | 33,223 | 35,008 | **5/60** |
| chat e2e（含 LLM） | 1 | 30 | 1.00 | **1,029** | 1,474 | 1,882 | 0 |
| chat e2e（含 LLM） | 10 | 60 | 1.69 | 4,826 | 9,585 | 10,226 | 0 |

注：c=1 的 P99 长尾（retrieve 7.1s / TTFT 18.5s）为首批请求冷启动（JIT/Milvus 预热/首会话），
属单机 dev 环境特征，不隐瞒。

## 2. 观察到的失败模式（真实记录，不修数字）

- **c=10 时 SSE 出现 5/60 次 HTTP 500**：根因为 **Hikari 连接池耗尽**
  （`Connection is not available, request timed out`）——SSE 长流与并发请求竞争 dev 默认连接池。
  这是 dev pool 配置下的真实容量边界，修复属于容量配置问题而非 benchmark 问题；
  生产 profile（`application-prod.yml` Hikari 20）需单独验证。
- **rerank 未测**：GPU reranker 服务离线（`rerank: DOWN`，hybrid 熔断降级路径生效中）。
  Reranker latency = _(unmeasured — 需 GPU 服务 + `RAG_RERANK_ENABLED=true` 复测)_。

## 3. 各阶段拆分（c=1，P50 ms，可从 artifact 重算）

| 阶段 | c=1 P50 (ms) | 说明 |
|---|---:|---|
| Retrieve（embed+hybrid+ACL） | 275 | rerank OFF |
| LLM TTFT | 688 | SSE 首内容事件 |
| Full RAG E2E | 1,029 | retrieve + LLM 生成 + 后处理 |
| — Rerank（Δ） | _(unmeasured)_ | GPU 离线 |

## 4. 未测场景（诚实标注）

| 场景 | 状态 | 原因 |
|---|---|---|
| c=50 / c=100 / c=500 | 未执行 | 单机 dev 环境 + 外部 LLM API 限流下无真实意义；按协议"不为简历数字强跑" |
| rerank ON 全套 | _(unmeasured)_ | GPU 服务离线（BLOCKED：外部资源） |
| Agentic 路径延迟 | 已有评测口径（pilot: ~16.0s mean, ×2.8） | 见 `docs/evaluation/` 冻结报告，不与本次 benchmark 混表 |

## 5. 再生方法

```bash
# 前置: chat-app 已起(hybrid或rerank模式如实记录), LLM_API_KEY 已配
python3 perf/benchmark/bench.py --concurrency 1 --n 30 --out perf/benchmark/result_c1.json
python3 perf/benchmark/bench.py --concurrency 10 --n 60 --out perf/benchmark/result_c10.json
# 原始样本在 result_c*.json, 本表全部数字可由其重算
```

（历史 Locust 框架保留于 `perf/locustfile*.py`；本报告由 `perf/benchmark/bench.py` 生成，
两者口径不同不混用。）
