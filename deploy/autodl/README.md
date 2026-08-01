# Autodl GPU 部署: BGE-Reranker-v2-m3

> 本机 M4 + Rosetta amd64 跑 ORT backend 单 query 3-10s, batch 翻倍; 12G native RAM 下 candle 还会 SIGSEGV。
> 迁到 Autodl GPU 后单 query ~50-200ms, **解决 V2 验收报告 §2 标的 P0 债**(recall 0.78→0.82)。

---

## 文件清单

| 文件 | 作用 |
|---|---|
| `reranker.Dockerfile` | 基于 TEI GPU 镜像, 模型 bind mount 不 bake |
| `start_reranker.sh` | 一键启动: 检查 runtime / 模型 / 拉镜像 / run |
| `download_model.py` | 从 hf-mirror 下 BGE-Reranker-v2-m3 全集(避开 hf-hub bug) |

## 启动步骤

### 1. 开 Autodl GPU 实例
- 镜像: `Ubuntu 22.04 + Docker`(预装 nvidia-container-toolkit 的更佳)
- 显卡: **≥ 16G 显存**(RTX 3090 / A10 / V100 都行)
- 实例启动后 ssh 进去

### 2. 下模型 + 启容器
```bash
# scp 本目录三个文件到 Autodl:/root/autodl-tmp/
# 或 git clone 项目到 Autodl 后切到 deploy/autodl/
cd /root/autodl-tmp

python3 download_model.py            # 下 2.2GB 模型 ~2-5min
./start_reranker.sh                  # 启动容器
```

### 3. Autodl 端口暴露
- Autodl 控制台 → 容器实例 → **自定义服务**
- 系统已自动把容器内 `8080` 映射到公网某个 `xxx.autodl.pro:NNNN`
- 记下该公网 URL(下文 `<autodl-pub-url>`)

### 4. 烟测(在 Autodl 里)
```bash
curl http://localhost:8080/health
curl -X POST http://localhost:8080/rerank \
  -H 'Content-Type: application/json' \
  -d '{"query":"Sentinel 怎么配置限流","documents":["Sentinel 用 SphU.entry 限流","Dubbo 异步调用配置"],"top_n":2}'
# 期望: 第二个文档排在前面(relevance_score 高)
```

### 5. 应用本机接入
`.env` 加 / 改:
```bash
RAG_RERANK_ENABLED=true
RAG_RERANK_BASE_URL=http://<autodl-pub-url>
```

重启应用 + 验证日志含 `retrieve.rerank_applied`:
```bash
make run  # 然后调 /chat 看日志
```

---

## 决策记录

| # | 决策 | 理由 |
|---|---|---|
| 1 | 用 TEI 而非 vLLM 起 reranker | TEI 是 HF 官方, Jina/Cohere 协议兼容; BGE-Reranker-v2-m3 是 cross-encoder 不是生成模型, vLLM 不适用 |
| 2 | bind mount 模型, 不 bake 进镜像 | 镜像通用, 模型可热替换; 2.2GB bake 镜像 push 慢 |
| 3 | HF_HUB_OFFLINE=1 | 防止 hf-hub 0.3.2 redirect bug 启动失败(本机 V2-A 坑 1 的延伸) |
| 4 | --dtype float16 | 7B 以下 reranker 在 16G 显存下剩 ~50% 容量; float32 没必要 |
| 5 | model_dir 默认 /root/autodl-tmp/ | Autodl 数据盘扩容便宜; 系统盘小 |

---

## 故障排查

| 症状 | 根因 + 处置 |
|---|---|
| `docker run` 报 `could not select device driver` | nvidia-container-toolkit 未装, Autodl 重装系统镜像选 + cuda toolkit 版 |
| 启动 5 分钟仍不 ready | docker logs 看具体错误; OOM → 换 24G 显存实例 |
| 应用调 rerank 503/504 | Autodl 自定义服务端口映射问题, 用 `curl <autodl-pub-url>/health` 直测 |
| rerank 比无 rerank 还慢 | 检查 RAG_RERANK_TOP_N 与 RetrieveService candidatePool, candidatePool=20 是上限 |
| recall 数字没变化 | chunk 没重新 index; 跑 `scripts/reindex_milvus.py` |
