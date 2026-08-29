#!/usr/bin/env python3
"""Temporary OpenAI-compatible BGE-M3 embedding service for GPU reindexing."""

from __future__ import annotations

import os
from typing import Any

import torch
import uvicorn
from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

MODEL_ID = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")
MODEL_REVISION = os.getenv("EMBEDDING_REVISION", "5617a9f61b028005a4858fdac845db406aefb181")
DEVICE = os.getenv("EMBEDDING_DEVICE", "cuda")
PORT = int(os.getenv("PORT", "6008"))

model = SentenceTransformer(
    MODEL_ID,
    revision=MODEL_REVISION,
    device=DEVICE,
    trust_remote_code=True,
)
model.max_seq_length = 8192
model.encode(["warmup"], normalize_embeddings=True, convert_to_numpy=True)

app = FastAPI(title="temporary-bge-m3-embedding")


class EmbeddingRequest(BaseModel):
    input: str | list[str]
    model: str | None = None
    dimensions: int | None = None


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "ok", "model": MODEL_ID, "revision": MODEL_REVISION, "device": DEVICE}


@app.get("/info")
def info() -> dict[str, Any]:
    return {
        "model_id": MODEL_ID,
        "model_revision": MODEL_REVISION,
        "model_dtype": str(model.dtype),
        "device": str(model.device),
        "normalized": True,
        "runtime": "sentence-transformers",
    }


@app.post("/v1/embeddings")
def embeddings(req: EmbeddingRequest) -> dict[str, Any]:
    texts = [req.input] if isinstance(req.input, str) else req.input
    with torch.inference_mode():
        vectors = model.encode(
            texts,
            batch_size=min(64, max(1, len(texts))),
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
    return {
        "object": "list",
        "model": MODEL_ID,
        "data": [
            {"object": "embedding", "index": index, "embedding": vector.tolist()}
            for index, vector in enumerate(vectors)
        ],
        "usage": {"prompt_tokens": 0, "total_tokens": 0},
    }


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=PORT, workers=1, log_level="info")
