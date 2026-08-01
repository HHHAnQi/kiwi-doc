#!/usr/bin/env bash
# ============================================================
# Autodl 上启动 BGE-Reranker-v2-m3 cross-encoder 的一键脚本
#
# 前提:
#   1. Autodl 已开 GPU 实例(≥ RTX 3090 / A10 / 任意 16G+ 显存), 系统镜像含 Docker + nvidia-container-toolkit
#   2. 模型文件已就位(路径 /root/autodl-tmp/models--BAAI--bge-reranker-v2-m3/)
#      下载方式见 download_model.py(已 commit 在本目录)
#
# 用法:
#   ./start_reranker.sh                 # 默认 model-path /root/autodl-tmp/bge-reranker-v2-m3
#   MODEL_DIR=/path/to/model ./start_reranker.sh
#
# 启动后:
#   - 容器名: ragdoc-reranker
#   - 监听: 0.0.0.0:8080(由 Autodl 自定义服务映射到公网)
#   - 健康检查: curl http://<host>:<port>/health
# ============================================================
set -euo pipefail

MODEL_DIR="${MODEL_DIR:-/root/autodl-tmp/bge-reranker-v2-m3}"
PORT="${PORT:-8080}"
DTYPE="${DTYPE:-float16}"
IMAGE_TAG="ghcr.io/huggingface/text-embeddings-inference:89-gpu"

# 详见 deploy/autodl/reranker.Dockerfile: 模型 bind mount, 不 bake 进镜像
echo "[1/4] 检测 Docker + nvidia runtime..."
if ! command -v docker &>/dev/null; then
    echo "ERROR: Docker 未安装; Autodl 系统镜像选 'Ubuntu 22.04 + Docker'" >&2
    exit 1
fi
if ! docker info 2>/dev/null | grep -q "Runtimes.*nvidia"; then
    echo "ERROR: nvidia-container-toolkit 未装; 参考 https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html" >&2
    exit 1
fi

echo "[2/4] 检测模型文件 $MODEL_DIR..."
if [ ! -d "$MODEL_DIR" ]; then
    echo "ERROR: 模型目录不存在。请先跑:" >&2
    echo "  python3 download_model.py  # 下到 /root/autodl-tmp/bge-reranker-v2-m3" >&2
    exit 1
fi
if [ ! -f "$MODEL_DIR/config.json" ]; then
    echo "ERROR: $MODEL_DIR/config.json 缺失, 模型目录不完整" >&2
    exit 1
fi

echo "[3/4] 拉镜像 $IMAGE_TAG (首次约 2-5 分钟)..."
docker pull "$IMAGE_TAG"

echo "[4/4] 启动容器..."
# 已有 ragdoc-reranker 先清掉
docker rm -f ragdoc-reranker 2>/dev/null || true
docker run -d \
    --name ragdoc-reranker \
    --runtime nvidia \
    --gpus all \
    --shm-size 1g \
    -p "${PORT}:80" \
    -v "${MODEL_DIR}:/data:ro" \
    --restart unless-stopped \
    -e HF_HUB_OFFLINE=1 \
    "$IMAGE_TAG" \
    --model-id /data \
    --port 80 \
    --dtype "$DTYPE"

echo ""
echo "✓ 启动中(首次加载模型 ~30-120s)"
echo "  容器状态: docker logs -f ragdoc-reranker"
echo "  健康检查: curl http://localhost:${PORT}/health"
echo ""
echo "应用本机改 .env:"
echo "  RAG_RERANK_ENABLED=true"
echo "  RAG_RERANK_BASE_URL=http://<autodl-pub-ip>:<autodl-mapped-port>"
echo ""
echo "烟测:"
echo "  curl -X POST http://localhost:${PORT}/rerank \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"query\":\"hello\",\"documents\":[\"hi world\",\"foo bar\"],\"top_n\":2}'"
