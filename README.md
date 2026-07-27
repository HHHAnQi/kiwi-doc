# rag-doc-platform

> 企业私有多模态 RAG 智能中台(V1 单体骨架)。
> 详细设计文档见上级目录 `企业私有多模态RAG智能中台-设计文档/`。

---

## 5 分钟启动

前置依赖:
- JDK 21
- Docker 24+ 与 Docker Compose v2
- (可选) GNU Make

```bash
# 1. 准备本地环境变量
make env                      # 从 .env.example 复制 .env

# 2. 启动中间件(MySQL / Redis / MinIO / Milvus,首次拉镜像约 3-5 分钟)
make up

# 3. 等待 Milvus 健康(约 30-60 秒)
make ps

# 4. 启动应用(本地开发用 bootRun)
make run
```

启动后:
- 健康检查: http://localhost:8080/actuator/health
- Swagger UI: http://localhost:8080/swagger-ui.html
- MinIO 控制台: http://localhost:9001 (用户 `minio` / 密码 `minio123`)

---

## 项目结构

```
rag-doc-platform/
├── platform-common/                  # 纯 Java: 错误体系 / DTO / 通用工具
├── platform-bootstrap/               # Spring Boot 主模块(启动入口)
│   └── src/main/java/com/xxx/ragdoc/
│       ├── interfaces/               # REST 接口层(Controller / DTO / Filter / 异常处理)
│       ├── application/              # 应用层(用例编排,端口注入)
│       │   └── document/port/        # 端口接口(FileStorage / DocumentRepository)
│       ├── domain/                   # 领域层(纯 POJO, 不依赖 Spring)
│       │   ├── document/             # Document 聚合 + 状态机 + 事件
│       │   └── shared/               # 跨上下文值对象(ContentHash / DocumentId / TraceId)
│       └── infrastructure/           # 基础设施(JPA / MinIO / Milvus / 配置)
│           ├── config/               # MinioConfig / OpenApiConfig
│           ├── persistence/jpa/      # DocumentEntity / Repository / Mapper
│           ├── persistence/minio/    # MinioFileStorage(FileStorage 实现)
│           └── parse/                # StubParsingTrigger(V2 替换为 Tika 实现)
├── deploy/docker-compose.yml         # 本地中间件一键起
├── scripts/                          # 工具脚本(init-milvus 等)
├── Makefile                          # 常用命令封装
└── .env.example                      # 本地配置模板
```

---

## V1 已实现范围

| 模块 | 状态 | 说明 |
|---|---|---|
| 上传 (`POST /api/v1/documents`) | ✅ | SHA256 幂等 + 类型/大小白名单 + MinIO 落盘 |
| 解析索引 | ⚠️ Stub | 仅状态机迁移到 PARSING,V2 接 Tika |
| 其他功能点 | ❌ | V1 后续补充 |

---

## 常用命令

```bash
make help           # 列所有目标
make up             # 起中间件
make down           # 停中间件
make run            # 启动应用
make test           # 跑单测 + ArchUnit
make lint           # Spotless 格式检查
make app            # 打 jar
```

---

## 资源画像(最低)

| 组件 | 内存 | 磁盘 |
|---|---|---|
| App + 中间件 | 4GB | 30GB |
| V2 起 + vLLM 推理 | 需独立 GPU(≥ 16GB 显存) | - |

详见 docs/architecture/performance.md。

---

## 设计文档导航

完整设计(架构 / 数据 / ADR / 13 维度框架)见上级目录:

```
../企业私有多模态RAG智能中台-设计文档/
├── README.md
├── docs/                         # 对外专业文档
└── .internal/                    # 内部决策记录(含13维度框架 / 评审纪要)
```
