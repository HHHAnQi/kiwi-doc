# Autodl GPU 部署: BGE-Reranker-v2-m3

> **V2 验收报告 §2 标的 P0 债**: 本机 M4 + Rosetta amd64 跑 ORT backend 单 query 3-10s, batch 翻倍;
> 12G native RAM 下 candle 还会 SIGSEGV。迁到 Autodl GPU 后单 query ~1s, recall 0.78→0.82(β 用户门槛)。

---

## 实际选型: 纯 Python (sentence_transformers) + CUDA, **不走 docker**

实测 Autodl Ubuntu 22.04 GPU 系统镜像**没装 docker daemon**, 但已预装 miniconda3 + CUDA + torch 2.8。
所以纯 Python 走 sentence_transformers.CrossEncoder **更轻量**:
- 不需要 docker pull / nvidia-container-toolkit
- 模型加载一次常驻显存, 单 query ~1s, batch 5 文档 ~30ms
- uvicorn + FastAPI 协议层与 BgeRerankClient 完全兼容(/rerank 端点)

`rerank_svc.py` 已在 `/root/autodl-tmp/` 就位(2026-08-01 实测可启动)。

模型路径(预下好): `/root/autodl-tmp/ms_cache/models/BAAI--bge-reranker-v2-m3/snapshots/master/`

---

## 启动步骤(已验证 2026-08-01; 2026-08-23 复验通过)

> **2026-08-23 复验记录**: 实例 SSH 端口现为 **37951**(历史 46908/49581 已失效,
> AutoDL 重启实例会换端口)。本机已 `ssh-copy-id` 免密; start_rerank.sh 启动 ~25s 后
> health OK, RTX 3090 显存占用 ~2.5GB, 烟测区分度 20x, 端到端 rerank_state=applied,
> RAGAS 全指标提升(faith +9.2pp / recall +7.5pp, 见 eval 报告)。

```bash
ssh -p <port> root@<autodl-host>      # 进 Autodl
cd /root/autodl-tmp

# 一条命令:start_rerank.sh 已就位(本会话产出), 干掉旧进程 + 重启
sh /root/autodl-tmp/start_rerank.sh

# 等 ~25s 模型加载 + warmup
# 验证
curl http://127.0.0.1:6006/health
# -> {"status":"ok","model":".../bge-reranker-v2-m3/snapshots/master","device":"cuda"}

# 烟测
curl -X POST http://127.0.0.1:6006/rerank \
  -H 'Content-Type: application/json' \
  -d '{"query":"Sentinel 怎么配置限流规则","documents":["SphU.entry 包裹资源受流控约束","Dubbo 异步调用通过 RpcContext.asyncCall 实现"],"top_n":2}'
# -> {"results":[{"index":0,"relevance_score":0.97},{"index":1,...}]}
```

---

## 暴露公网(必须用户控制台手动)

容器内监听 0.0.0.0:6006, 但 Autodl 默认只把 **22 (ssh)** 暴露公网。
要让应用本机能访问, 二选一:

### 方式 A: Autodl 自定义服务(推荐)
1. Autodl 控制台 → 你的 GPU 实例 → **"自定义服务"** 标签
2. 启用 → 给一个独立的 `xxx.autodl.pro:NNNN` 公网域名
3. 默认映射成容器 6006 端口
4. 应用本机 `.env`: `RAG_RERANK_BASE_URL=http://xxx.autodl.pro:NNNN`

### 方式 B: SSH 隧道(临时, 不需控制台操作)
本机终端跑:
```bash
ssh -L 19006:127.0.0.1:6006 -p 37951 root@connect.nmb2.seetacloud.com -N
# 当前常用形式(chat-app .env 指向本机 8084):
#   ssh -p 37951 -N -L 8084:localhost:6006 root@connect.nmb2.seetacloud.com
# 留这个终端开着
```
应用本机 `.env`: `RAG_RERANK_BASE_URL=http://localhost:19006`

方式 B 缺点: 隧道断线需手动重连; `autossh` 可保活但本机需装。

---

## 应用本机接入

`.env` 加:
```bash
RAG_RERANK_ENABLED=true
RAG_RERANK_BASE_URL=http://<autodl-custom-service-url-or-localhost-tunnel>
RAG_RERANK_MODEL=BAAI/bge-reranker-v2-m3
```

启动 app, 触发一次 chat, 日志应含:
```
retrieve.rerank_applied candidates=20, final_n=5, top1_score=0.9x
```

---

## 故障排查(实测过的)

| 症状 | 根因 + 处置 |
|---|---|
| `bind 8080 address already in use` | start_rerank.sh 内部已 kill 旧进程, 但若你手动 spawn 多份会冲突。先 `pkill -9 -f rerank_svc 2>/dev/null; ps -ef \| grep [r]erank` 清干净 |
| `[Errno 98] bind on 0.0.0.0:6006` | 6006 被 tensorboard 占用? 改 PORT=6008 |
| curl `Connection refused` 公网 | 自定义服务没启用 / 映射错端口; 检查 `curl 127.0.0.1:6006/health` 仍可, 说明容器内 OK, 公网映射问题 |
| 调用 504 timeout | RAG_RERANK_TIMEOUT_MS 默认 30000ms, Rosetta 慢; 但 GPU 下应该是 ms 级, 检查 `/health` 显存是否释放 |
| recall 数字没变 | chunks 没重新索引; 跑 `scripts/reindex_milvus.py` |

---

## start_rerank.sh 内容(已验证)

```bash
#!/bin/bash
pkill -9 -f rerank_svc 2>/dev/null
ps -ef | grep [r]erank_svc.py | awk '{print $2}' | xargs -r kill -9 2>/dev/null
sleep 2
cd /root/autodl-tmp
export PORT=6006
setsid /root/miniconda3/bin/python3 rerank_svc.py </dev/null >/root/autodl-tmp/rerank_6006.out 2>&1 &
disown 2>/dev/null
echo "spawned $!"
```

关键点:
- `pkill -9 -f rerank_svc` **不能直接用**—— 会误杀自己的 sshd 父进程(命令字符串含 'rerank_svc')。必须用 `ps -ef \| grep [r]erank_svc.py` 把 `[r]` 加上让它不匹配 grep 自身。这是已踩过的坑。
- `setsid + </dev/null + &` 三件套确保 ssh 会话退出后服务不挂。`nohup` 也可但 setsid 更彻底脱离 controlling tty。
- `disown` 让 shell 退出不杀子进程; bash 支持, sh 不支持(警告但不影响)。

---

## 决策记录

| # | 决策 | 理由 |
|---|---|---|
| 1 | 纯 Python 而非 TEI/容器 | Autodl Ubuntu 22.04 系统镜像无 docker; miniconda3+CUDA+torch 已预装; 纯 Python 轻量 |
| 2 | CrossEncoder 而非 FlagEmbedding | sentence_transformers API 更通用, 未来要换别的 cross-encoder 改一行即可 |
| 3 | 监听 6006 而非默认 8080 | Autodl "自定义服务" 默认抓 6006 → 公网; tensorboard 已占 6007 |
| 4 | 模型 stays in `/root/autodl-tmp/` | Autodl 数据盘扩容便宜, 不占系统盘 |
| 5 | 不 bake 进 git | `/root/autodl-tmp/rerank_svc.py` 是远程资产, 不入 repo; 项目内 `deploy/autodl/` 仅放文档 |

---

## 4090D 实例(2026-08-25)

- **服务器**: `ssh -p 30442 root@121.48.170.6`(4090D, 24GB)
- 已 ssh-copy-id 免密
- 模型路径: `/root/autodl-tmp/hf_cache/models--BAAI--bge-reranker-v2-m3/snapshots/...`
- 服务脚本: `/root/autodl-tmp/rerank_svc.py` + `start_rerank.sh`
- 下载方式: `HF_ENDPOINT=https://hf-mirror.com HF_HUB_DISABLE_XET=1`(xet 协议需禁用)
- 隧道: `ssh -p 30442 -N -L 8084:localhost:6006 root@121.48.170.6`
