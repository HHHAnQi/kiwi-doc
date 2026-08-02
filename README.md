# rag-doc-platform

> 企业私有多模态 RAG 智能中台(V3-W1 进行中)。详细设计文档见上级目录 `企业私有多模态RAG智能中台-设计文档/`,
> 关键 ADR 见 `docs/adr/`。

---

## 5 分钟启动

前置依赖:
- JDK 17 (V3 暂用 17,V4 接虚拟线程后切 21)
- Docker 24+ 与 Docker Compose v2
- (可选) GNU Make

```bash
# 1. 准备本地环境变量
make env                      # 从 .env.example 复制 .env

# 2. 启动中间件(MySQL / Redis / MinIO / Milvus, 首次拉镜像约 3-5 分钟)
make up

# 3. 等待 Milvus 健康(约 30-60 秒)
make ps

# 4. 启动应用(本地开发用 bootRun)
make run

# 5. (V3 第 1 周起) parser-service 独立进程, 如需异步解析链路:
#    java -jar parser-service/build/libs/parser-service.jar --spring.profiles.active=dev
```

启动后:
- 健康检查: http://localhost:8080/actuator/health
- Swagger UI: http://localhost:8080/swagger-ui.html
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
│       ├── infrastructure/      # JPA / MinIO / Milvus / DashScope / Tika / 配置
│       └── event/               # 领域事件发布
├── parser-service/              # V3 独立异步解析服务(RocketMQ 驱动, 进行中)
├── deploy/docker-compose.yml    # 本地中间件一键起
├── docs/adr/                    # 架构决策记录(ADR-0001 ~ 0010)
├── docs/v3/                     # V3 详细 spec(如 parser-service-spec.md)
├── eval/                        # RAGAS 评测脚本 + 真实 baseline 报告(ADR-0008)
├── scripts/                     # 工具脚本
├── Makefile                     # 常用命令封装
└── .env.example                 # 本地配置模板
```

---

## 实现进度(V3-W1 收尾)

| 能力 | 状态 | 说明 |
|---|---|---|
| 上传 `POST /api/v1/documents` | ✅ | SHA256 幂等 + 类型/大小白名单 + MinIO 落盘 |
| 解析索引(同步) | ✅ | Tika 抽文本 + Parent-Child 切片 + BGE-M3 embed + Milvus + 状态机 |
| Chunk 切片 | ✅ | flat / parent_child 双模式 + Markdown 结构感知 + child overlap |
| 向量检索 | ✅ | Milvus hybrid(dense BGE-M3 + sparse BM25) + RRF 融合 + 业务元数据过滤 |
| Reranker | ✅ | bge-reranker-v2-m3(Autodl 部署, ADR-0009 / SOP 见 ops) |
| Chat | ✅ | 4 档降级(EMPTY_KB/NO_RECALL/LLM_DEGRADED/OK) + trace_id 贯穿 |
| Chat SSE 流式 | ✅ | V3-W1: Flux<ChatStreamEvent> 首token <1.5s |
| Feedback + trace | ✅ | feedbacks 软引用 chat_traces.trace_id(ADR-0003) |
| parser-service 拆分 | 🟡 进行中 | Commit 1(DDL+domain)/ Commit 2(共享层下沉)/ Commit 3(MQ worker,待做) |
| 评价体系 | ✅ | ADR-0008 baseline 锁(faith 0.60 / recall 0.43, 100 docs corpus) |
| Langfuse trace 接入 | ❌ | V3-W3 做(ADR-0006) |
| docker-compose 全栈压测 | 🟡 | compose 有, Locust 100 并发压测待做 |
| k3s / K8s | ❌ | ADR-0010 砍掉, 推 V4 流量来时再启 |

V3 范围与砍项理由详见 `docs/adr/adr-0010-v3-rebalance-cut-rag-llm-k8s.md`。

---

## 常用命令

```bash
make help           # 列所有目标
make up             # 起中间件
make down           # 停中间件
make run            # 启动 chat-app
make test           # 跑单测 + ArchUnit
make lint           # Spotless 格式检查
make app            # 打 jar
```

---

## 资源画像(最低)

| 组件 | 内存 | 磁盘 |
|---|---|---|
| App + 中间件 | 4GB | 30GB |
| + Autodl Reranker / BGE | 需独立 GPU(≥ 16GB 显存) | - |

---

## 评价与数据(诚实)

详见 `eval/` 目录, 这里只放对外 baseline:

| 配置 | faith | recall | 备注 |
|---|---|---|---|
| V3 P2 baseline (100 docs, parent-child) | 0.5950 | 0.4316 | Airbnb `glm-4-plus` judge |
| V2-P4 +reranker (50 docs, flat) | 0.6711 | 0.5711 | 历史 baseline, 不同 judge 不可直比 |

corpus 完整性是 RAG 数字最大杠杆: 50 docs → 100 docs 后 recall +23pp。

---

## 设计文档导航

```
../企业私有多模态RAG智能中台-设计文档/   # 原始设计(架构 / 数据 / 13 维度框架)
docs/adr/                                # ADR-0001 ~ 0010 (含 Superseded 决策, 供回溯)
docs/v3/                                 # V3 spec (parser-service-spec.md 等)
docs/operations/                         # Autodl reranker SOP / 部署运维
eval/                                    # RAGAS 评测脚本 + baseline 报告
```
