# rag-doc-platform

> 企业私有多模态 RAG 智能中台(**V3 验收门槛已命中**, faith 0.88 / recall 0.90 实测)。
> 详细设计文档见上级目录 `企业私有多模态RAG智能中台-设计文档/`,
> 关键 ADR 见 `docs/adr/`, V3 spec / runbook / 验收报告见 `docs/v3/`。

---

## 5 分钟启动

前置依赖:
- JDK 17 (V3 暂用 17,V4 接虚拟线程后切 21)
- Docker 24+ 与 Docker Compose v2
- (可选) GNU Make
- (V3-W3 后) Autodl GPU 服务器(BGE-M3 / Reranker) 或本地 docker 起同等容器

```bash
# 1. 准备本地环境变量
make env                      # 从 .env.example 复制 .env

# 2. 启动中间件(MySQL / Redis / MinIO / Milvus / RocketMQ / BGE-M3 / Reranker)
make up                       # 首次拉镜像约 8-12 分钟(BGE/Reranker 大)

# 3. 等待 Milvus + BGE 健康(约 1-3 分钟, BGE start_period 240s)
make ps

# 4. 启 chat-app(默认 sync 模式, 不依赖 RocketMQ+parser-service 也跑通)
make run

# 5. (V3-W1 起的 async 路径) 切 async + 启 parser-service
#    RAG_PARSER_MODE=async ./gradlew :platform-bootstrap:bootRun
#    ./gradlew :parser-service:bootJar
#    java -jar parser-service/build/libs/parser-service.jar --spring.profiles.active=dev --server.port=8093

# 6. (V3-W3 起) Langfuse trace 接入(可选)
#    docker run langfuse/langfuse:latest 或 docker compose langfuse 主仓 compose
#    export LANGFUSE_ENABLED=true LANGFUSE_PUBLIC_KEY=... LANGFUSE_SECRET_KEY=...
```

启动后:
- chat-app 健康检查: http://localhost:8080/actuator/health
- Swagger UI: http://localhost:8080/swagger-ui.html
- parser-service 健康检查(V3 async 时): http://localhost:8093/actuator/health
- MinIO 控制台: http://localhost:9001 (用户 `minio` / 密码 `minio123`)
- Langfuse 控制台(V3-W3, enabled 时): http://localhost:3000

### 前端 SPA(可选,V3 已落地)

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173(被占用则自动落到 5174)
```

dev 模式下 `vite.config.ts` 把 `/api/*` 反向代理到 `http://localhost:8080`,
因此**不需要**在后端额外开 CORS。前置条件:chat-app 已 `make run` 起来。
打开浏览器即可:左侧上传/选择文档 → 右侧输入问题 → SSE 流式回答 → 引用卡片 → 👍/👎 反馈。
细节见 [`frontend/README.md`](frontend/README.md)。

---

## 项目结构

```
rag-doc-platform/
├── platform-common/             # 共享层(domain / port / chunking / 异常 / DTO / TraceObserver 端口)
├── platform-bootstrap/          # chat-app: Spring Boot 主模块
│   └── src/main/java/com/xxx/ragdoc/
│       ├── interfaces/rest/     # Controller / DTO / Filter / 异常处理
│       ├── application/         # 应用服务(DocumentUpload/Chat/Retrieve/Feedback)
│       ├── infrastructure/      # JPA / MinIO / Milvus / DashScope / Tika / MQ producer / Langfuse trace
│       └── event/               # 领域事件发布
├── parser-service/              # V3-W1 独立异步解析服务(RocketMQ 驱动, 已落地 DoD-1/2/4)
│   └── src/main/java/com/xxx/ragdoc/parser/
│       ├── application/         # ParseTaskService(状态机) / ParseWorker(Tika+checkpoint)
│       └── infrastructure/      # ParseTaskConsumer(RocketMQListener) / Visibility Timeout Scheduler
├── frontend/                    # V3 前端 SPA(React 19 + Vite 8 + Tailwind v4 + Zustand 5)
│   └── src/
│       ├── api/                 # client/documents/chat(SSE)/feedback
│       ├── components/          # StatusBadge/UploadDropzone/Sidebar/StateBanner/CitationCard/FeedbackBar/ChatMessage/ChatWindow
│       ├── store/               # useDocStore / useChatStore(zustand)
│       └── types/api.ts         # 与后端 DTO 对齐的 TS 类型
├── deploy/docker-compose.yml    # 本地中间件 + RocketMQ broker 一键起
├── docs/adr/                    # 架构决策记录(ADR-0001 ~ 0010)
├── docs/v3/                     # V3 spec / kill-9 runbook / 验收报告 / P0 runbook(待加)
├── docs/operations/             # Autodl reranker SOP / eval-regression SOP
├── eval/                        # RAGAS 评测脚本 + 真实 baseline 报告(ADR-0008)
├── scripts/                     # 工具脚本 + v3-kill-9-drill.sh
├── .github/workflows/           # CI( lint+test ) + eval-regression( ADR-0008 D3 )
├── Makefile                     # 常用命令封装
└── .env.example                 # 本地配置模板
```

---

## 实现进度(V3 主验收门槛已命中 ✨, 2026-08-02)

### RAG 质量真值(P0 run final)

✨ **V3 验收门槛已命中**: faith 0.88 / precision 0.87 / recall 0.90 远超设计目标(faith ≥0.75 / recall ≥0.65)。

详见 [eval/baseline_v3_judge_plus.md](eval/baseline_v3_judge_plus.md) + [docs/v3/v3-acceptance-report.md §4](docs/v3/v3-acceptance-report.md)。

### 已落地能力

| 能力 | 状态 | 说明 |
|---|---|---|
| 上传 `POST /api/v1/documents` | ✅ | SHA256 幂等 + 类型/大小白名单 + MinIO 落盘 |
| 解析索引(同步 sync 模式) | ✅ | Tika + Parent-Child 切片 + BGE-M3 embed + Milvus + 状态机 |
| 解析索引(异步 async 模式) | ✅ **V3-W1** | MQ producer + parser-service consumer + chunk-level 续点 |
| Chunk 切片 | ✅ | flat / parent_child 双模式 + Markdown 结构感知 + child overlap |
| 向量检索 | ✅ | Milvus hybrid(dense BGE-M3 + sparse BM25) + RRF 融合 + 业务元数据过滤 |
| Reranker | ✅ | bge-reranker-v2-m3(Autodl 部署, SOP 见 docs/operations) |
| Chat | ✅ | 4 档降级(EMPTY_KB/NO_RECALL/LLM_DEGRADED/OK) + trace_id 贯穿 |
| Chat SSE 流式 | ✅ | Flux<ChatStreamEvent> 首token <1.5s |
| Feedback + trace | ✅ | feedbacks 软引用 chat_traces.trace_id(ADR-0003) |
| parser-service kill -9 故障韧性 | ✅ **V3-W1** | 心跳 job + 续点 + 演练脚本(实跑 log 待 mac 窗口) |
| Langfuse trace 接入(同步 chat 路径) | ✅ **V3-W3** | No-op 兜底 + HTTP ingestion client, enabled=false 零开销 |
| RAGAS CI 门禁(nightly + label-gate) | ✅ **V3-W3** | ADR-0008 D3 落地, PR 带 eval-impact label 触发, regression 自动开 issue |

### 未做 / 推后项(诚实标注)

| 项 | 推到哪 | 原因 |
|---|---|---|
| Langfuse SSE(chatStream) 路径接入 | V3.5 / V4 | Flux 流式 token 完成 endTrace 设计复杂度高于同步 |
| DoD-2 端到端集成测试(poison msg → DLQ) | V3.5 | 单测覆盖了状态机, 端到端 IT 推后 |
| kill -9 演练实跑 PASS log | mac/Autodl 窗口 | 跑 5-10min 出截图入验收报告(不阻塞 Accepted) |
| noise 校准(nightly 跑 ≥3 次 mean ± std) | V3 末 nightly | 当前 baseline 单跑, threshold 临时 5pp buffer |
| corpus 扩到 500+ docs | V4 | V3 已跨过验收门槛, 大 corpus 是 V4 RAG 调优主线 |
| 真实用户 query 流量校准 | V4 | extractive GT 是 LLM 生成题, 真实 query 才是真验收 |
| docker-compose Locust 100 并发压测 | V4 + 真流量 | ADR-0010 砍掉, 0 用户场景演不出 HP 价值 |
| k3s / K8s | V4 + 真流量 | ADR-0007 Superseded, 同上 |

V3 范围与砍项理由详见 `docs/adr/adr-0010-v3-rebalance-cut-rag-llm-k8s.md`。
V3 真实完成度 / 进 V4 启动门槛判据见 `docs/v3/v3-acceptance-report.md`。

---

## V3 DoD 验收对照(spec §9)

| DoD | 状态 | 验证 |
|---|---|---|
| **DoD-1** kill -9 优雅降级 | ✅ 代码 | `scripts/v3-kill-9-drill.sh` + runbook; 🟡 实跑 PASS log 待 |
| **DoD-2** 重试续解析 + DLQ | 🟡 单测 cover 状态机 | 端到端 IT 推 V3-W3 末 |
| **DoD-3** p95 < 2s | ❌ | Locust 100 推 V4(0 用户场景演不出) |
| **DoD-4** 中断续点 | ✅ 代码 | `ParseWorker.checkpointProgress` 每 10 chunks flush; 🟡 实跑 PASS log 待 |
| **DoD-5** trace(Langfuse) | 🟡 同步路径接入 | SSE 路径 + ChatStreamEvent 五点接入推 V3-W3 末 |
| **DoD-6** 灰度降级(sync↔async) | ✅ 代码 | `@ConditionalOnProperty` + DocumentUploadService 端口零改动 |

---

## 常用命令

```bash
make help           # 列所有目标
make up             # 起中间件(含 RocketMQ)
make down           # 停中间件
make run            # 启动 chat-app(默认 sync)
make test           # 跑单测 + ArchUnit
make lint           # Spotless 格式检查
make app            # 打 jar

# 异步路径启动(V3-W1)
RAG_PARSER_MODE=async ./gradlew :platform-bootstrap:bootRun
./gradlew :parser-service:bootJar && java -jar parser-service/build/libs/parser-service.jar

# V3-W1 DoD 演练
./scripts/v3-kill-9-drill.sh
```

---

## 资源画像(最低)

| 组件 | 内存 | 磁盘 |
|---|---|---|
| chat-app + 中间件 + RocketMQ | 5GB | 35GB |
| + Autodl Reranker / BGE | 需独立 GPU(≥ 16GB 显存) | - |
| + Langfuse(自部署) | + 0.5GB | + 1GB |

---

## 评价与数据(诚实)

详见 `eval/` 目录, 这里只放对外 baseline:

| 配置 | faith | precision | recall | 备注 |
|---|---|---|---|---|
| **V3 P0 run final ✨ (100 docs, 30 题 extractive GT, rerank ON)** | **0.8849** | **0.8661** | **0.9000** | `glm-4-plus` judge, 跨过 V3 合格线 |
| V3 P0 run1 (100 docs, 80 题, rerank OFF, 改写 GT) | 0.6072 | 0.4968 | 0.3486 | 历史过程数字, 跑前配置错(rerank OFF + GT 模板污染) |
| V2-P4 +reranker (50 docs, flat) | 0.6711 | 0.7193 | 0.5711 | `glm-4-flash` judge, 与 P0 +plus judge 不可直比 |

**V3 验收门槛 已命中**: ADR-0008 设计目标 faith ≥0.75 / recall ≥0.65, P0 run final **远超**(faith +13.5pp / recall +25pp)。

corpus 完整性是 RAG 数字最大杠杆: 50 docs → 100 docs 后 recall +23pp; reranker ON 净增 ≈ +50pp across metrics(V3-W3 extractive GT 让增益更显性)。
**V4 主线候选**: 真实用户 query 流量校准 + HyDE / query rewrite 二阶优化 + corpus 扩 500+。

---

## CI / 评测门禁

| Workflow | 触发 | 用途 |
|---|---|---|
| `.github/workflows/ci.yml` | 每个 PR / push | spotless + test + ArchUnit 守护 |
| `.github/workflows/eval-regression.yml` | nightly + PR 带 `eval-impact` label + 手动 | RAGAS 30 题评测 + baseline 对比, regression 自动开 issue |

eval-regression 详见 `docs/operations/eval-regression-sop.md`。

**必须打 `eval-impact` label 的 PR**: 改切片 / 检索 / embedding / corpus / reranker / prompt。

---

## 设计文档导航

```
../企业私有多模态RAG智能中台-设计文档/       # 原始设计(架构 / 数据 / 13 维度框架)
docs/adr/                                    # ADR-0001 ~ 0010 (含 Superseded 决策, 供回溯)
docs/v3/                                     # V3 spec(parser-service-spec.md) / kill-9 runbook / V3 验收报告
docs/operations/                             # Autodl reranker SOP / eval-regression SOP
eval/                                        # RAGAS 评测脚本 + baseline 报告
scripts/v3-kill-9-drill.sh                   # V3-W1 DoD-1 硬资产演练
.github/workflows/                           # CI + eval-regression(ADR-0008 D3)
```
