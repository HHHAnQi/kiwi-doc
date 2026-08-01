"""
Autodl bge-reranker-v2-m3 service.

Endpoints:
  POST /v1/rerank   Body: {"query": str, "documents": [str], "top_n": int=5}
                     Resp: {"results": [{"index": int, "score": float, "document": str}]}
  GET  /health

Why own service not TEI container:
  - Autodl Ubuntu 22.04 image has no docker daemon installed; pure-python+GPU is lighter.
  - Model loaded warm once, ~30ms / 5-doc rerank on RTX 3090.
"""
import os
import sys
import logging
from typing import List
from fastapi import FastAPI
from pydantic import BaseModel
import uvicorn

logging.basicConfig(level=logging.INFO, format='[%(asctime)s] %(levelname)s %(message)s')
log = logging.getLogger("rerank_svc")

MODEL_ID = os.getenv("RERANKER_MODEL", "/root/autodl-tmp/ms_cache/models/BAAI--bge-reranker-v2-m3/snapshots/master")
# 句内文本最大 512 tokens (与原 Reranker 一致). sentence-transformers 内部处理截断.
DEVICE = os.getenv("RERANKER_DEVICE", "cuda")
PORT = int(os.getenv("PORT", "8080"))

log.info(f"loading model from {MODEL_ID} on {DEVICE}...")
try:
    from sentence_transformers import CrossEncoder
    model = CrossEncoder(MODEL_ID, device=DEVICE, max_length=512)
    # 触发 warmup
    model.predict([("warmup", "warmup sentence")])
    log.info("warmup done")
except Exception as e:
    log.error(f"model load failed: {e}")
    sys.exit(1)

app = FastAPI(title="bge-reranker-v2-m3 service")


class RerankReq(BaseModel):
    query: str
    documents: List[str]
    top_n: int = 5
    # TEI/Jina/Cohere 兼容字段: 别名
    top_n_alias: int = 5


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_ID, "device": DEVICE}


@app.post("/v1/rerank")
def rerank_v1(req: RerankReq):
    """与 BgeRerankClient(Java)兼容路径, 返回 {results:[{index,score,document}]}."""
    return _do_rerank(req)


@app.post("/rerank")
def rerank_tei_compat(req: RerankReq):
    """TEI / Jina / Cohere 协议兼容路径:
    响应 {results:[{index, relevance_score}]} 不带 document(节省带宽)。
    本机 BgeRerankClient.java 调的就是这条。
    """
    return _do_rerank(req, tei_compat=True)


def _do_rerank(req: RerankReq, tei_compat: bool = False):
    pairs = [(req.query, doc) for doc in req.documents]
    scores = model.predict(pairs).tolist()
    indexed = [{"index": i, "score": float(s)} for i, s in enumerate(scores)]
    indexed.sort(key=lambda x: x["score"], reverse=True)
    if tei_compat:
        # TEI 协议: 返回 relevance_score 而非 score, 无 document
        results = [{"index": r["index"], "relevance_score": r["score"]} for r in indexed[:max(req.top_n, 0)]]
        return {"results": results}
    else:
        # 本服务原生协议: 带 document 文本便于人肉调试
        results = [{"index": r["index"], "score": r["score"], "document": req.documents[r["index"]]} for r in indexed[:max(req.top_n, 0)]]
        return {"results": results}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PORT, workers=1, log_level="info")
