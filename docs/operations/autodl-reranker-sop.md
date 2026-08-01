# Autodl Reranker 部署 SOP

## 适用场景
V2/V3 评测期临时跑 bge-reranker-v2-m3 cross-encoder 评测, 不长期在线。
平时关机省钱, 评测再开。

## 一、首次部署已完成(本次会话产出)

| 资产 | 位置 | 备注 |
|---|---|---|
| Reranker 服务脚本 | Autodl: `/root/autodl-tmp/rerank_svc.py` | fastapi + CrossEncoder, TEI 兼容 |
| 模型 snapshot | Autodl: `/root/autodl-tmp/ms_cache/models/BAAI--bge-reranker-v2-m3/snapshots/master/` | modelscope 下载, 2.2G |
| 本机 ssh tunnel 脚本 | 本机: `/tmp/tunnel.exp` | expect 包装 ssh -L |
| Java 侧无改动 | rag-doc-platform | yml rerank.* 字段已全部 env 化 |

## 二、开关机 SOP

### 开机(预计 2-3 min)
1. Autodl 控制台手动开机
2. 本机执行:
   ```bash
   # 1) 启动 ssh tunnel (本机 18080 → 远端 8080)
   nohup /tmp/tunnel.exp > /tmp/tunnel.log 2>&1 & disown
   # 2) 远端启动 Reranker 服务
   /tmp/ssh_autodl.exp 'cd /root/autodl-tmp && nohup setsid /root/miniconda3/bin/python rerank_svc.py > svc.log 2>&1 < /dev/null &'
   # 3) 等 30s 模型加载, 然后 health check
   sleep 30
   curl -s http://localhost:18080/health
   ```
3. 重启本机 rag-doc-platform:
   ```bash
   RAG_RERANK_ENABLED=true RAG_RERANK_BASE_URL=http://localhost:18080 \
   (其它 env...) ./gradlew bootRun
   ```

### 关机(预计 30s)
1. 本机: `pkill -f tunnel.exp; pkill -f "ssh.*18080"`
2. Autodl 控制台手动「关机」(无 GPU 模式, 不释放磁盘)

## 三、计费经验

- RTX 3090 24G 实例: ¥2-5/h (按市场价浮动)
- 单次评测会话: 1-2h (含模型加载 30s + RAGAS 100 题 8min × 3 跑 = 30min)
- 月度成本预估: 4-8 次评测 = 8-16h = ¥20-80

## 四、协议契约(供未来调试)

Reranker 服务暴露 2 个端点:

### GET /health
```json
{"status":"ok","model":"...","device":"cuda"}
```

### POST /v1/rerank (本服务原生)
```json
请求: {"query": str, "documents": [str], "top_n": int=5}
响应: {"results": [{"index": int, "score": float, "document": str}]}
```

### POST /rerank (TEI/Jina/Cohere 兼容, Java BgeRerankClient 调的就是这条)
```json
请求: 同上
响应: {"results": [{"index": int, "relevance_score": float}]}
```

## 五、已知问题与避坑

1. **huggingface_hub 直接下载会 401**: cas-server.xethub.hf.co 不支持 xet assets via mirror. 必须用 modelscope 或 hf-mirror.com
2. **直接 ssh & 后台启动会被 SIGHUP 杀**: 必须用 `nohup setsid ... < /dev/null &`
3. **nohup 不够**: 必须 setsid 完全 detach 控制终端
4. **ssh tunnel -R vs -L**: 本机访问远端服务用 -L (local forward), 不要用错成 -R
5. **expect 包装**: Autodl 要求密码登录, 不能 ssh key, 因此所有自动化必须 expect 喂密码
