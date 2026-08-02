# 生产部署 Runbook (V3-W4)

> 这份文档把 V3 已落地的部署套件（Dockerfile + nginx + docker-compose + Flyway）串成
> 三个**可执行、可回滚**的部署路径：dev-smoke / 单机 docker-compose / K8s(ad-hoc, V4)。
> 不写"未来其它方案", 只写"今天能跑通的真实命令"。

---

## 0. 部署模式选型

| 模式 | 用途 | 何时用 |
|---|---|---|
| **A. dev-smoke** | 本地 mac 旧路子: `make up` + `./gradlew bootRun` + `npm run dev` | 开发联调/单机内测 |
| **B. 全容器 docker-compose** | frontend + chat-app + 中间件全 image 化 | 单机 prod / 客户演示 / 演练 |
| **C. K8s + Helm** | 多副本 + HPA + service mesh | ⏸ 推 V4(ADR-0007 Superseded by 0010) |

模式 A 见主 README «5 分钟启动», 本文重点写 **B**（含模式 A → B 升级到 prod 的步骤）。

---

## 1. 镜像构建

### 1.1 chat-app + parser-service（同一 jar 或两 jar）

```bash
# 在 repo 根目录
./gradlew :platform-bootstrap:bootJar        # → platform-bootstrap/build/libs/*.jar
./gradlew :parser-service:bootJar            # → parser-service/build/libs/*.jar

# 假设有 Dockerfile (V4 待补); 当前阶段直接 java -jar 跑也合规
# 推荐: 写个 chat-app Dockerfile(java:17-slim + bootJar) 后补
```

> 当前 ADR-0010 决定: chat-app 暂不强行 image 化(jar 直跑够用), K8s 补时再 image 化。

### 1.2 frontend（已落地 Dockerfile）

```bash
cd frontend
# 默认 VITE_API_BASE 留空 = 走同源相对路径, 由本镜像内 nginx 反代后端
docker build -t ragdoc/frontend:v3 .
# 跨域直连后端时:
docker build -t ragdoc/frontend:v3 --build-arg VITE_API_BASE=https://api.example.com .
```

产物: `ragdoc/frontend:v3`, 端口 80, 内含 `/healthz` + `location /api/v1/chat/sse` 关 buffering。

### 1.3 推到 registry（按需）

```bash
docker tag ragdoc/frontend:v3 <registry>/ragdoc/frontend:v3
docker push <registry>/ragdoc/frontend:v3
```

---

## 2. 模式 B: docker-compose 全栈

### 2.1 准备 .env

```bash
cp .env.example .env
vim .env  # 至少改: MYSQL_ROOT_PASSWORD/MINIO 真密码/JWT 替换 dev-token

# V3-W4 OPS-V3-B 新增(均可空, 默认走 .env.example 路径):
# export BGE_M3_CACHE_DIR=/opt/models/bge-m3-cache        # 非 mac 机器
# export BGE_RERANKER_DIR=/opt/models/bge-reranker-v2-m3
```

### 2.2 启动中间件 + frontend(不含 chat-app, chat-app 当前仍 jar 跑)

```bash
make up                          # 起 mysql/redis/minio/milvus/rocketmq/bge(infra)
make ps                          # 等待 bge-m3 healthcheck 起来(start_period 240s)

# 起 frontend 容器(profile gated, 不影响 infra-only 流程)
docker compose --profile frontend -f deploy/docker-compose.yml up -d --build frontend
docker compose -f deploy/docker-compose.yml logs -f frontend
# 容器内 /healthz 通即 OK
curl http://localhost:8088/healthz
```

### 2.3 启 chat-app + parser-service(jar 模式)

```bash
# chat-app(默认 sync 模式)
RAG_PARSER_MODE=sync ./gradlew :platform-bootstrap:bootRun --args="--spring.profiles.active=prod"
# 切异步:
RAG_PARSER_MODE=async ./gradlew :platform-bootstrap:bootRun --args="--spring.profiles.active=prod"

# parser-service(async 模式才需要)
./gradlew :parser-service:bootJar
java -jar parser-service/build/libs/parser-service.jar \
  --spring.profiles.active=prod --server.port=8093
```

### 2.4 nginx 路径通断验证

```bash
# frontend container 内 nginx 反代 chat-app(upstream: chat-app:8080),
# 如果 chat-app 是 jar 在 host 跑, 把 nginx.conf upstream 改 host.docker.internal:8080(restart frontend container)
curl http://localhost:8088/                        # SPA
curl http://localhost:8088/healthz                 # → ok
curl http://localhost:8088/api/v1/documents?size=1 # 反代命中 chat-app
# SSE 关键验证(buffering off):
curl -N -X POST http://localhost:8088/api/v1/chat/sse -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' -d '{"query":"hi","top_k":2}'
# 应看到 event:citations / event:delta 立即返回, 而不是憋 30s 再一坨。
```

---

## 3. 升级 / 回滚

### 3.1 frontend 升级

```bash
git pull && cd frontend && npm ci && docker build -t ragdoc/frontend:v3 .
docker compose -f deploy/docker-compose.yml up -d --build --force-recreate frontend
```

回滚: `docker tag ragdoc/frontend:<prev> ragdoc/frontend:v3 && docker compose up -d --force-recreate frontend`

### 3.2 chat-app 升级(滚动+ health gate)

```bash
# jar 模式无 rolling, 但有 health gate:
./gradlew bootJar
pkill -f RagDocApplication && sleep 3
nohup java -jar platform-bootstrap/build/libs/*.jar --spring.profiles.active=prod &
sleep 30 && curl -fs http://localhost:8080/actuator/health
# 200 才认为起得来; 非 200 立即 pk kill + 跑老 jar 回滚
```

### 3.3 DB migration（Flyway 自动)

Flyway 在 bootRun 自动跑 V1-V6(V6 = chunks unique constraint, OPS-V3-B 联动)。

**回滚**：
- V6 是 ADD index，无数据迁移，回滚 = `ALTER TABLE chunks DROP INDEX uk_doc_seq_type;`
- 但 Flyway 不在 git history 删文件, 必须写 `U6__undo.sql` 或手工 DDL,
  并 DELETE `flyway_schema_history WHERE version=6`。本 V3 没写 undo 脚本。

---

## 4. 健康检查与监控

| 检查对象 | 命令 | 预期 |
|---|---|---|
| chat-app | `curl /actuator/health` | `{"status":"UP"}` 200 |
| parser-service | `curl :8093/actuator/health` | (同上) |
| frontend | `curl :8088/healthz` | `ok` 200 |
| MySQL | `docker exec ragdoc-mysql mysqladmin ping` | `mysqld is alive` |
| Milvus | `curl :9091/healthz` | `OK` |
| RocketMQ broker | `docker exec ragdoc-rmqbroker ps -ef\|grep mqbroker` | 有进程 |
| BGE-M3 | `curl :8081/health` | 200(Via 文档) |

**监控**(V4 占位): OTel + Prometheus + Grafana 待接; V3 暂只靠 docker healthcheck
+ Flyway 起动 fail-fast。

---

## 5. 故障与应急

| 症状 | 排查路径 |
|---|---|
| `/api/v1/chat/sse` 卡死无 token 流出 | (1)浏览器 DevTools 看 Network → 检查响应 Content-Type=text/event-stream; (2)在 frontend container 内 `nginx -T \| grep proxy_buffering` 必须含 off; (3)再看 chat-app `/actuator/health` 是否 UP |
| 上传后 status 卡 PARSING | (1)检查 RocketMQ broker + namesrv 是否 healthy;(2)`docker logs ragdoc-parser-service`(若起); (3)查 `parse_tasks` 表 `retry_count`/`error_message` |
| chat-app 启动报 Flyway fail | (1)读 stack 找是哪一句 SQL;(2)`SELECT * FROM flyway_schema_history WHERE success=0;` 看 fail 行;(3)DELETE failed 行,修 SQL,重启 |
| 软删文档仍能 GET detail(DEV-V3-C 已修) | 不应再出现;如出现说明 buildV6-C 代码未 deploy |
| NonUniqueResultException(DEV-V3-B 已修) | 不应再出现;DB 唯一索引 + 代码层 chunkType 消歧双兜底 |

---

## 6. 安全清单(prod 上线前)

- [ ] `.env`: MYSQL_ROOT_PASSWORD / MINIO 密码全部换非默认值
- [ ] APP_DEV_TOKEN 改成难猜的随机串; prod 应另写 JWT/SSO
- [ ] chat-app / parser-service 走 prod profile(关闭 dev debug log)
- [ ] nginx X-Frame-Options/nosniff/Referrer-Policy 已在 `deploy/nginx.conf` 默认开
- [ ] Langfuse: enabled 开后接 secret_key(若不上, 保持 enabled=false 零开销)
- [ ] Autodl reranker: 生产次数敏感 → 加 rate limit + 防火墙白名单

---

## 7. DoD-1(ops 部分)演练

按 `docs/v3/kill-9-drill-runbook.md` 跑 `scripts/v3-kill-9-drill.sh`,
**PASS log 入档**才认 DoD-1 完整收尾(PM-V3-B)。

---

## 附录: 已知 prod 缺口(诚实)

| 缺口 | 影响 | 计划 |
|---|---|---|
| 无 chat-app Dockerfile | 横向扩容不便 | V4 收 K8s 时一起写 |
| 无 Locust 100 并发基线 | p95 < 2s 无数 | V4 + 真流量后跑 |
| 无 OTel/Prometheus | 仅 healthcheck, 无应用监控 | V4 接 OTel Collector |
| 无 Helm chart | K8s 部署脚本缺 | V4 K8s 主线时一并交付 |
| 无 Sentry/前端错误上报 | JS 报错黑洞 | 前端 ErrorBoundary 已留钩子, V4 接 transport |
