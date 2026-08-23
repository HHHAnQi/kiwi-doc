# RAG 评测量化体系与 2026-08-23 复跑报告

> 本文是"如何评估量化一个 RAG 系统"的完整答案：指标体系设计 → 评委治理 → 门禁与基线
> 管理 → 本次全量复跑的流程、结果与可信度边界。面经第 3-4 层追问（"指标怎么来的？怎么
> 证明是这个模块带来的？"）的答案全部在本文。

---

## 一、评估体系总览（四层）

### 1.1 指标分层：检索侧 + 生成侧 + 拒答分离 + 多轮

| 层 | 指标 | 回答的问题 | 实现 |
|---|---|---|---|
| 检索侧（IR） | Recall@K / P@K / HitRate / MRR / NDCG | 找得到吗？位次好吗？ | `eval/metrics/retrieval_metrics.py`（纯函数，可单测） |
| 生成侧（RAGAS 四件套） | faithfulness / answer_relevancy / context_precision / context_recall | 答案忠实吗？切题吗？证据覆盖吗？ | `eval/ragas_pipeline.py` |
| **拒答分离** | refusal_rate / faith_on_answered / faith_on_refused | 把"诚实拒答"和"幻觉"分开——RAGAS 把两者都判 0 分，混在一起会低估真实能力 | 自研（`ragas_pipeline.py` Phase 2.0.2） |
| 多轮（G1-G5 gate） | 指代消解 / 抗污染 / 压缩 fidelity / 话题漂移 | 多轮体系各组件真的在工作吗？ | `eval/multi_turn/run_multi_turn_eval.py` |

### 1.2 评委治理（LLM-as-judge 的可信度建设）

- **物理隔离**：judge 走独立命名空间 `JUDGE_LLM_PROVIDER_*`（DeepSeek），绝不复用业务
  LLM（GLM-4-plus）配置——同源 judge 会系统性高估自家答案；
- **异族双 judge**：DeepSeek + Qwen 两族可交叉验证，已量化偏差（DeepSeek 比 Qwen 严 ~7pp）；
- **尺刻度验证**：`faith_on_refused` 应≈0（拒答文案被判为幻觉的比率）——它是 judge 本身
  是否工作的 sentinel；
- **温度=0.1 + thinking 兼容补丁**：降低 judge 随机性。

### 1.3 基线治理（防"指标通货膨胀"）

- `EVAL_BASELINE_CERT.md`：题集 SHA256 + commit hash 锁定，任何指标变动可追溯到代码变更；
- **单变量判据**：≥3pp 才视为有效提升（吸收 judge 噪声）；
- **自我审计文化**：`gold_leakage_audit.md` 曾自查出 pilot 题集 100% 标注泄漏并判 FAIL；
  抽取式 GT（ground_truth 抄 chunk 原文）对 faithfulness 有系统性高估，已如实记录；
- **回归门禁**：CI `-3%` 阻断（`.github/workflows/eval-regression.yml`）+ badcase 六类
  分类回归（state 不漂移 / gold 命中 / 位次不退步 / 答案等价 / 反向 SAFETY）。

---

## 二、本次复跑的环境与变更项

2026-08-22/23 完成一轮全链路修复后的完整复跑。环境与旧基线的**全部差异**如下（这是
读数前必须知道的）：

| 项 | 旧基线（EVAL_BASELINE_CERT） | 本次复跑 | 对可比性的影响 |
|---|---|---|---|
| 语料 | 150+ docs | 165 docs / 3074 chunks（重建） | ≈等价（同源文件） |
| 题集 | 80 题锁定 | 100 题 curated | 谨慎对比 |
| embedding | 本地 BGE-M3 | 智谱 embedding-3 @1024（本地 TEI 容器劣化到 233s/请求，见 §4.3） | **不可比项** |
| 检索 | dense+BM25+RRF，评测时 rerank ON | hybrid 默认 + **Contextual Retrieval 前缀**（新增） | 提升项 |
| 多轮 | SSE 主路径断链（死代码） | **SSE 多轮贯通** + 鹦鹉检测修复 + 引用对齐 | 修复项 |
| rerank | ON（GPU 隧道） | OFF→ON 各跑一轮（对照） | 本次新增对照 |
| P0 安全修复 | — | chunk 鉴权 / PRIVATE 文档 / 默认 token | 不影响指标 |

**结论**：rerank OFF vs ON 的**组内对照完全可比**（唯一变量）；与旧基线的绝对值对比
仅作方向性参考。

---

## 三、复跑流程（端到端）

```
1. 基础设施        docker compose 起 MySQL/Redis/MinIO/Milvus/TEI/RocketMQ
2. 应用启动        chat-app(8080) + parser-service(8093)，全新库 Flyway V1→V23
3. 语料灌入        bulk_upload_corpus.py → 165 docs（async 链路: outbox→MQ→ParseWorker）
4. 索引            chunk → 脱敏 → 注入扫描 → 切片 → [Contextual 前缀+] embed → Milvus
5. RAGAS × 2 轮    rerank OFF 一轮 + rerank ON 一轮（同题集同 judge，单变量对照）
6. 多轮 gate × 1   G1-G5 与旧报告逐 gate 对比
7. reranker        AutoDL RTX 3090 起 bge-reranker-v2-m3，SSH 隧道 8084→6006
```

### 3.1 RAGAS 主成绩（100 题，judge=DeepSeek-chat，单变量对照）

| 指标 | rerank OFF | rerank ON | Δ |
|---|---|---|---|
| faithfulness | 0.747 | **0.840** | **+9.2pp** |
| faith_on_answered（真实 RAG 能力） | 0.768 | **0.854** | **+8.6pp** |
| answer_relevancy | 0.690 | **0.768** | +7.7pp |
| context_recall | 0.450 | **0.525** | +7.5pp |
| context_precision | 0.506 | **0.562** | +5.6pp |
| refusal_rate | 4.0% | 4.0% | 持平 |

**rerank 价值的一句话证据**：同一 query 下 top1 的 hybrid 原始分仅 0.028（几乎无区分度），
cross-encoder 精排后 0.98——bi-encoder 召回 + cross-encoder 精排的分工在真实数据上成立。

**拒答率 4% vs 旧基线 16.25%**：方向性改善（hybrid 默认生效 + contextual 前缀 + 本轮题集
差异），说明"找得到证据"的能力增强后模型更少放弃回答。

### 3.2 多轮 gate（旧 → 新）

| Gate | 内容 | 旧 | 新 | 判定 |
|---|---|---|---|---|
| G1 | 单轮 baseline ±3pp 不退化 | PASS | **PASS** | **最关键回归**：多轮贯通/引用对齐/配置改动没有伤到主路径 |
| G2 | 指代消解（20 会话） | 2/20 | 3/20 | 方向对但远未达标——瓶颈已从"鹦鹉误杀"转移到改写 LLM 质量 |
| G3 | 抗污染（10 会话） | 1/10 | 1/10 | 持平 |
| G4 | 压缩 fidelity（5 会话） | 3/5 | 3/5 | 持平 |
| G5 | 话题漂移（50 会话） | 30/50 | **37/50** | +14pp（检索增强 + 多轮贯通的复合收益） |

---

## 四、可信度边界（诚实声明）

1. **抽取式 GT 高估**：题集由 LLM 从 chunk 生成、ground_truth 抄 chunk 原文，
   context_recall 实质测"能否检索到出题 chunk"，faithfulness 偏乐观。缓解方向
   （已在推进）：人工标注 + 改写后校验的非原文 GT（`gold_annotation_guideline.md`）；
2. **本轮换 embedding 模型**：与旧基线的绝对值不可比，组内对照（rerank ON/OFF）不受影响；
3. **单轮采样**：每配置跑 1 轮而非 mean±std（RUNS=3 的完整协议见 `p0-eval-run.sh`），
   ±1-2pp 内的差异不应过度解读；
4. **faith_on_refused**（OFF 0.25 / ON 0.50）基于仅 4 个拒答样本，噪声极大，只作尺刻度参考；
5. **题集同源生成**：出题 LLM 与被测系统共存召回分布偏置，answer_relevancy 用本地 BGE-M3
   embedding 评分与检索 embedding 非同源（本轮检索用云 embedding），相关性指标仅供参考。

## 五、复现

```bash
# 前置: docker compose 中间件全栈 + AutoDL reranker
ssh -p 37951 root@connect.nmb2.seetacloud.com "sh /root/autodl-tmp/start_rerank.sh"   # GPU reranker
ssh -p 37951 -N -L 8084:localhost:6006 root@connect.nmb2.seetacloud.com &             # 隧道
make run                                                                               # chat-app
./gradlew :parser-service:bootRun --args="--spring.profiles.active=dev"                # parser
EMBEDDING_BASE_URL=<云endpoint> EMBEDDING_API_KEY=<key> EMBEDDING_MODEL=embedding-3 \
EMBEDDING_DIMENSIONS=1024 ...                                                          # 云端 embed 提速
python3 scripts/bulk_upload_corpus.py                                                  # 灌库
RUNS=3 SKIP_CURATE=1 ./scripts/p0-eval-run.sh                                          # RAGAS mean±std
python3 eval/multi_turn/run_multi_turn_eval.py                                         # 多轮 gate
```

## 六、本轮评估驱动的修复清单（评测→修复→复测闭环）

| 发现 | 修复 | 复测结果 |
|---|---|---|
| 多轮 gate 全 FAIL 且无修复闭环 | SSE conversationId 贯通 + 鹦鹉检测改编辑相似度 | G1 保持 PASS，G2 +1，G5 +7 |
| 评测配置(hybrid+rerank)与线上默认(dense)漂移 | retrieve 默认 hybrid | 线上=评测口径 |
| 引用编号错位（history 占 [1] + 截断后 citations 不对齐） | marker 隔离 + 双闸门预算器 + citations 同步截断 | 回答带正确 [n] 引用 |
| reranker 本地 Rosetta 不可用（331 次崩溃重启） | 迁回 AutoDL GPU + 隧道 | rerank_state=applied，全指标 +5~9pp |
| 索引吞吐崩溃（embed 超时→熔断→DLQ 风暴） | 并发信号量 + CB 阈值 300s + 云端 embed | 165 docs 从 4h+ 降到 6min，零失败 |
