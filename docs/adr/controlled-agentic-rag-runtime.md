# 受控 Agentic RAG 运行架构与实施边界

- Status: Accepted for staged rollout
- Date: 2026-08-13
- Scope: 架构与研发基线；算法效果优化另行评审

## 1. 产品定位

RagDoc 不再只定位为 Native RAG。系统保留三条互不冒充的执行路径：

1. `RAG`：显式固定为 Classic/Hybrid RAG，作为低延迟基线与回归对照。
2. `AUTO`：Router 根据任务类型选择 Classic、Targeted、Fixed Workflow 或 Planned Agent。
3. `AGENTIC`：调用方显式选择受控 Agent，能力开关开启后直达 Planned Agent；关闭时返回明确错误，不静默降级。

Agentic 的竞争力来自“按证据缺口规划下一步”，而不是简单增加一次 LLM 调用：

`Router/Explicit Mode → Requirement → Planner → Tool → Evidence → Sufficiency → Replan → Grounded Answer`

## 2. 当前已经具备的工程能力

- Planner：规则模板与模型 Planner 两种 Provider，模型输出继续通过计划校验器，不把 Prompt 当安全边界。
- Tool：语义检索、关键词检索、元数据检索、文档读取，统一 Registry 和输入输出契约。
- Evidence：按租户权限获取证据并累计证据 ID，答案基于证据生成。
- Sufficiency：规则/模型充分性判断；证据不足可触发一次有上限 Replan。
- Governance：Run/Step 状态机、CAS 唯一终态、工具调用/步数/时间/Token 预算、取消信号。
- Persistence：Run/Step、Plan、预算、用量和证据摘要持久化，可审计。
- Recovery：陈旧非终态 Run 由守护任务 CAS 转为 `SYSTEM_FAILED`，避免节点宕机后永久悬挂。
- Streaming：阻塞式 Planner/Tool 工作移出 WebFlux 事件循环；客户端取消传播到协作式取消令牌。

## 3. 安全边界

- 默认关闭：`planner.enabled=false`、`planned-pipeline-enabled=false`。
- 显式 `AGENTIC` 也必须通过服务端双开关，客户端不能绕过部署门禁。
- Tool allowlist 由服务端构造，Planner 只能选择允许的工具和版本。
- Planner 失败、Schema 非法、预算超限、权限不足、终态 CAS 冲突都失败关闭。
- 当前工具应保持只读。引入写工具前必须新增风险等级、参数策略、人审、幂等键与补偿审计。
- 恢复阶段暂不自动续跑远程 Tool：仅凭数据库状态无法证明调用是否已发生，盲目续跑可能重复副作用。

## 4. 分阶段上线计划

### Gate A：工程链路（当前阶段）

- 编译和单元测试通过。
- 用户完成代码审查后，再执行 MySQL/Milvus/ES/RocketMQ/LLM 的整体启动链路测试。
- 验证显式 `RAG/AUTO/AGENTIC` 三种入口、Run/Step 状态、超时、取消、Broker 失败和重启回收。

### Gate B：可恢复执行

- 给 Tool 增加调用幂等键与结果快照。
- 增加执行 owner lease/heartbeat 和 checkpoint schema。
- 只有可证明幂等的只读 Step 才允许断点续跑；其他 Run 安全终止并创建新 Run。
- 增加人工取消、重放、查看 Plan/Step/Evidence 的运维接口与审计日志。

### Gate C：Agentic 竞争力验证

- 先补齐 multi-hop、evidence-conflict、tool-failure-recovery、budget-timeout 数据切片。
- 用相同 Gold Evidence 和相同检索/Token 预算对比 Hybrid RAG，避免“多调用几次所以更好”的伪收益。
- 核心指标：任务成功率、Gold Evidence Recall、Faithfulness、平均 Tool 调用数、P95 延迟和单请求成本。
- 未通过效果/成本 Gate 时，`AUTO` 保持优先路由到 Hybrid RAG；显式 `AGENTIC` 仅用于灰度。

### Gate D：受控行动型 Agent

- 先选一个垂直场景，接入 1–2 个只读业务 Tool。
- 再引入带人审的写 Tool；禁止直接演进成无边界通用 Agent 或多 Agent。

## 5. 暂不纳入本轮

- 不调整 Embedding、RRF、Rerank、HyDE 等算法参数。
- 不声明 Agentic 已优于 Hybrid；必须以 Gate C 的配对评测结果为准。
- 不在用户审查前启动外部中间件或执行整体链路测试。
