# BGE-Reranker-v2-m3 on Autodl GPU 服务器
#
# 本机(M4, 12G RAM, Rosetta amd64)跑 ORT backend 时单 query ~3-10s, batch 翻倍;
# reranker 是 V2 验收报告 §2 标的 P0 债, recall 0.78→0.82 关键变量。
# 迁到 Autodl GPU 后单 query ~50-200ms, 解决本机 SIGSEGV/OOM + Rosetta 慢推理双痛点。
#
# 设计选型原因(沿用本机 V2 决策):
#   - 用 HuggingFace text-embeddings-inference(TEI) 的 GPU 镜像, 与本机 ORT backend 协议一致(/rerank 端点)
#   - 同样走本地 bind mount 绕过 hf-hub 0.3.2 redirect bug(见 V2-A 实施进度坑 1)
#   - 模型可以本机跑 hf-mirror 下好后 scp 到 Autodl, 或 Autodl 直接从 hf-mirror pull(海外带宽足)
#
# 启动后:
#   - 公网 IP:端口(Autodl 默认开放容器 8080 → 公网某端口, 见 Autodl 容器实例的"自定义服务"标签)
#   - 应用本机改 RAG_RERANK_BASE_URL=http://<autodl-ip>:<port> + RAG_RERANK_ENABLED=true
#
from ghcr.io/huggingface/text-embeddings-inference:89-gpu

# 模型由 host bind mount 挂入 /data, 不 bake 进镜像(让镜像通用, 模型可热替换)
# 入口启动用: --model-id /data --port 80 --dtype float16
#   dtype float16 让 7B 以下 reranker 在 16G 显存下剩 ~50% 容量给并发
EXPOSE 80

ENTRYPOINT ["/usr/local/bin/text-embeddings-inference"]
CMD ["--model-id", "/data", "--port", "80", "--dtype", "float16"]
