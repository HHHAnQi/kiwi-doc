# rag-doc-platform

> 企业私有多模态 RAG 智能中台(**V3-W1 收工**)。详细设计文档见上级目录 `企业私有多模态RAG智能中台-设计文档/`,
> 关键 ADR 见 `docs/adr/`, V3 详细 spec 见 `docs/v3/`。

---

## 5 分钟启动

前置依赖:
- JDK 17 (V3 暂用 17,V4 接虚拟线程后切 21)
- Docker 24+ 与 Docker Compose v2
- (可选) GNU Make

```bash
# 1. 准备本地环境变量
make env                      # 从 .env.example 复制 .env

# 2. 启动中间件(MySQL / Redis / MinIO / Milvus / RocketMQ, 首次拉镜像约 5-8 分钟)
make up

# 3. 等待 Milvus 健康(约 30-60 秒)
make ps

# 4. 启 chat-app(默认 sync 模式, 不依赖 RocketMQ+parser-service 也跑通)
make run

# 5. (V3 async 路径) 切 async + 启 parser-service
#    RAG_PARSER_MODE=async ./gradlew :platform-bootstrap:bootRun
#    ./gradlew :parser-service:bootJar
#    java -jar parser-service/build/libs/parser-service.jar --spring.profiles.active=dev --server.port=8093
```

启动后:
- chat-app 健康检查: http://localhost:8080/actuator/health
- Swagger UI: http://localhost:8080/swagger-ui.html
- parser-service 健康检查(V3 async 时): http://localhost:8093/actuator/health
- MinIO 控制台: http://localhost:9001 (用户 `minio` / 密码 `minio123`)

---

## 项目结构

```
rag-doc-platform/
├── platform-common/             # 共享层(domain / port / chunking / 异常 / DTO)
├── platform-bootstrap/          # chat-app: Spring Boot 主模块, 含 chat / 上传 / retrieve / feedback
│   └── src/main/java/com/xxx/ragdoc/
│       ├── interfaces/rest/     # Controller / DTO / Filter / 异常处理
│       ├── application/         # 应用服务(DocumentUpload/Chat/Retrieve/Feedback)
│       ├── infrastructure/      # JPA / MinIO / Milvus / DashScope / Tika / 配置 / MQ producer
│       └── event/               # 领域事件发布
├── parser-service/              # V3-W1 独立异步解析服务(RocketMQ 驱动, 已落地 DoD-1/2/4)
│   └── src/main/java/com/xxx/ragdoc/parser/
│       ├── application/         # ParseTaskService(状态机) / ParseWorker(Tika+checkpoint)
│       └── infrastructure/      # ParseTaskConsumer(RocketMQListener) / VisibilityTimeoutScheduler
├── deploy/docker-compose.yml    # 本地中间件 + RocketMQ broker 一键起
├── docs/adr/                    # 架构决策记录(ADR-0001 ~ 0010)
├── docs/v3/                     # V3 spec + kill-9 演练 runbook
├── eval/                        # RAGAS 评测脚本 + 真实 baseline 报告(ADR-0008)
├── scripts/                     # 工具脚本 + v3-kill-9-drill.sh(演练硬资产)
├── Makefile                     # 常用命令封装
└── .env.example                 # 本地配置模板
```

---

## 实现进度(V3-W1 收工, 2026-08-02)

### 已落地能力

| 能力 | 状态 | 说明 |
|---|---|---|
| 上传 `POST /api/v1/documents` | ✅ | SHA256 幂等 + 类型/大小白名单 + MinIO 落盘 |
| 解析索引(同步 sync 模式) | ✅ | Tika + Parent-Child 切片 + BGE-M3 embed + Milvus + 状态机 |
| 解析索引(异步 async 模式) | ✅ **V3-W1 新** | MQ producer + parser-service consumer + chunk-level 续点 |
| Chunk 切片 | ✅ | flat / parent_child 双模式 + Markdown 结构感知 + child overlap |
| 向量检索 | ✅ | Milvus hybrid(dense BGE-M3 + sparse BM25) + RRF 融合 + 业务元数据过滤 |
| Reranker | ✅ | bge-reranker-v2-m3(Autodl 部署, ADR-0009 / SOP 见 ops) |
| Chat | ✅ | 4 档降级(EMPTY_KB/NO_RECALL/LLM_DEGRADED/OK) + trace_id 贯穿 |
| Chat SSE 流式 | ✅ | Flux<ChatStreamEvent> 首token <1.5s |
| Feedback + trace | ✅ | feedbacks 软引用 chat_traces.trace_id(ADR-0003) |
| 评价体系 | ✅ | ADR-0008 baseline 锁(faith 0.60 / recall 0.43, 100 docs corpus) |
| parser-service kill -9 故障韧性 | ✅ **V3-W1 新** | 心跳 job 回收 zombie worker + 续点解析, 演练脚本就绪 |

### 未做 / 推后项(诚实标注)

| 项 | 推到哪 | 原因 |
|---|---|---|
| Langfuse trace 接入 | V3-W3 | ADR-0006 已锁, chat_traces 数据已就位, 加 SDK 一两小时 |
| docker-compose Locust 100 并发压测 | V3-W4 | DoD-3 p95 <2s, 0 用户场景暂不需 |
| ADR-0008 RAGAS 落 GitHub Actions | V3-W2 | 评测脚本齐, CI job 未自动跑 |
| corpus 扩到 150+ docs + 重 curate | V3-W2 起步 | 当前 100 docs corpus, 翻倍 + 重新跑 baseline 才见效 |
| k3s / K8s | V4 + 真流量 | ADR-0010 砍掉, 演不出 HPA 价值 |

V3 范围与砍项理由详见 `docs/adr/adr-0010-v3-rebalance-cut-rag-llm-k8s.md`。

---

## V3-W1 parser-service DoD 验收(spec §9)

| DoD | 状态 | 验证 |
|---|---|---|
| **DoD-1** kill -9 优雅降级 | ✅ | `scripts/v3-kill-9-drill.sh` + runbook `docs/v3/kill-9-drill-runbook.md` |
| **DoD-2** 重试续解析 | 🟡 部分 | 代码 done(`ParseTaskService.markFailed` retry_count++); 端到端集成测试待补 |
| **DoD-4** 中断续点 | ✅ | `ParseWorker.checkpointProgress` 每 10 chunks flush; 演练脚本验之 |

(DoD-3 p95<2s / DoD-5 Langfuse / DoD-6 灰度 在 V3 后续周)

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

---

## 评价与数据(诚实)

详见 `eval/` 目录, 这里只放对外 baseline:

| 配置 | faith | recall | 备注 |
|---|---|---|---|
| V3 P2 baseline (100 docs, parent-child) | 0.5950 | 0.4316 | `glm-4-plus` judge |
| V2-P4 +reranker (50 docs, flat) | 0.6711 | 0.5711 | 历史 baseline, 不同 judge 不可直比 |

corpus 完整性是 RAG 数字最大杠杆: 50 docs → 100 docs 后 recall +23pp。
**V3-W2 主线就在补 corpus 到 150+ docs + 重新 curate ground truth**。

---

## 设计文档导航

```
../企业私有多模态RAG智能中台-设计文档/   # 原始设计(架构 / 数据 / 13 维度框架)
docs/adr/                                # ADR-0001 ~ 0010 (含 Superseded 决策, 供回溯)
docs/v3/                                 # V3 spec (parser-service-spec.md / kill-9-drill-runbook.md)
docs/operations/                         # Autodl reranker SOP / 部署运维
eval/                                    # RAGAS 评测脚本 + baseline 报告
scripts/v3-kill-9-drill.sh               # V3-W1 DoD-1 硬资产演练
```
