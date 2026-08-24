# 简历项目稿(kiwi-doc / rag-doc-platform)

> 数据来源: docs/evaluation/evidence-provenance.md(每个数字的题集/judge/协议/出处)。
> 所有数字均为实测, 无估算。版本: 2026-08-24。

---

## 简历条目(定稿版, 按此粘贴)

**kiwi-doc — 企业级 RAG 文档问答平台**(个人项目 | Java/Spring Boot 3 + React 19 + Milvus)

面向私有技术文档(165 docs/3074 chunks)的问答系统: 混合检索 + 重排 + 引用可溯源生成,
多轮对话与 Agentic RAG 路径, 四层评测体系全程守护; 核心方法论是**评测驱动开发** ——
由评测暴露问题、定位根因、修复后复测闭环。

- **RAG 检索/生成链路**: 设计混合检索(dense + BM25 RRF 融合)→ cross-encoder GPU 重排
  → Contextual Retrieval(入库向量拼接文档上下文前缀)→ 引用编号对齐生成的完整链路;
  100 题消融实测 rerank 贡献 faithfulness +9.2pp / recall +7.5pp, 拒答率 16%→6%。

- **四层评测体系(自研拒答分离指标)**: 检索侧(Recall@K/MRR/NDCG) + 生成侧(RAGAS 四件套)
  + 拒答分离(把"诚实拒答"与"幻觉"分开计量, 避免低估真实能力) + 多轮 Gate(G1-G5);
  **异族 LLM-as-Judge 治理**(DeepSeek 评分 GLM 生成, 物理隔离杜绝同源偏置,
  双 judge 交叉验证并量化偏差 7pp), 基线证书锁定题集 SHA256 + commit,
  RUNS=3 完整协议 —— 终版 faithfulness **0.777±0.003**。

- **评测驱动的深度修复**: 累计定位并修复 20+ 实测缺陷 —— 跨租户越权(3 处)、
  SSE 多轮断链、引用编号错位、异步索引风暴(超时→熔断→死信连环, 4h→6min)、
  sufficiency 判定器把异质证据误判为冲突(误杀率 63%→18%); 三次发现"评测失真"
  (金标语料失配/判定口径不公/度量混杂), **先修尺子再修系统**, 多轮指代消解
  gate 从 3/20 修复至 **18/20(90%)**。

- **Agentic RAG 完整实现与对照评测**: Plan-Execute 执行协调器(LLM 查询分解/
  五工具编排/充分性双层判定/增量 Replan) + 六维预算 + CAS 状态机 + checkpoint
  + 只读审计端点, 平台能力封装为 MCP Server; 完成与 Classic 的对照评测
  (pass^k/延迟/引用三维), 五轮校准将准确率 11.7%→30%, 得出**基于数据的启用边界
  结论**(当前语料规模下单 Agent+混合检索更优)——负结果全程可追溯。

- **工程可靠性**: MySQL 为事实源、Milvus 为派生索引的信任模型(召回后逐条回库校验);
  异步索引链路(outbox + 租约 + visibility timeout + 对账, kill -9 演练验证);
  Agent token 记账(实测 4910 tokens/run); prod profile 凭据零默认值;
  依赖健康检测上线首日即捕获一次真实链路故障。

- **前端(React 19)**: SSE 流式对话(手解帧 + 看门狗)、可展开引用卡片(chunk 原文与
  邻居)、会话列表管理、**Agent 执行过程可视化**(plan 步骤/工具/证据数/耗时折叠面板)。

---

## 电梯稿(30 秒口头版)

"我做了一个企业 RAG 问答平台, 最大的特点是评测驱动: 建了四层指标和异族 judge,
所有能力先有评测再上线。比如 rerank 是消融测出 +9 个点才开的; agentic 路径做了
完整实现和对照, 数据显示当前语料下不如单次检索, 我就把这个负结果完整记录了
—— 包括五轮校准把它的准确率从 12% 拉到 30% 的过程。修过 20 多个实测缺陷,
印象最深的是三次'评测挂了先怀疑尺子': 多轮 gate 3/20, 修完题集和判定口径后
真实水平是 18/20。"

## 追问防线(每条 bullet 的支撑细节)

| 预期追问 | 你的答案要点 |
|---|---|
| "faithfulness 怎么算的? judge 可信吗?" | RAGAS 协议, judge=DeepSeek temp 0.1 与业务 GLM 物理隔离; 双 judge 偏差量化 7pp; faith_on_refused≈0 做尺刻度哨兵; 边界: 未做人工 κ 对齐、抽取式 GT 偏乐观(主动讲) |
| "rerank 为什么 +9pp?" | bi-encoder 召回分数几乎无区分度(实测 top1 hybrid 分 0.028 vs 精排后 0.98), cross-encoder 才把真相关顶到前面; 100 题对照, 其余配置不变 |
| "agentic 为什么不赢?" | 3074 chunks 单库、抽取式单跳题集不是 agentic 甜区; 分解后证据+50% 但 sufficiency 拒答 25%、延迟×2.8; 结论与业界共识一致(需要更大语料/多源/外呼); 修复了 10 个"从未通电"的存量缺陷才跑通 |
| "G2 3/20→18/20 怎么做到的?" | 归因三部曲: ①金标语料失配(~1/4 事实不存在, SQL 审计实锤) ②判定口径混杂(答案判定≠改写判定, 加 X-Effective-Query 头改 query-vs-query) ③改写器升级(few-shot+主路由); 剩 2 个失败是真实改写缺陷 |
| "索引风暴是什么?" | Rosetta 模拟的 embed 吞吐低 → 并发排队 → 集体超时 → CB 熔断(30s 慢调用阈值误判) → 任务重排风暴 → DLQ; 三层修复: 并发信号量 + CB 阈值 300s + 云端 embedding |
| "安全做过什么?" | 三个越权面(chunk 接口零校验/PRIVATE 文档硬编码 TENANT/公开默认 token 入库) + Deny-by-Default + 检索双层 ACL(Milvus 预过滤 + MySQL 回库校验) + prompt 注入双层防御 |
| "token 成本怎么控制?" | 六维预算(步数/工具/LLM/token/成本/时长) + 路由分层(简单题不过 Agent) + token 记账实测; agentic 延迟×2.8 是结论依据之一 |

## 使用说明

- 简历上 6 条 bullet 建议按 JD 裁剪: RAG 岗保 1/2/3/5, Agent 岗保 2/4/5/6,
  全栈岗保 1/5/6 + 2 精简;
- "个人项目"诚实标注; 仓库 github.com/HHHAnQi/kiwi-doc(main 线性历史 40 commit,
  12 ADR, 16 篇评测/审计文档可查);
- 所有数字的原始出处: docs/evaluation/evidence-provenance.md。
