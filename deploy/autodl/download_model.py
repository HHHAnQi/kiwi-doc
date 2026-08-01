#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Autodl 上从 hf-mirror 镜像下载 BGE-Reranker-v2-m3 全套文件。

Autodl 国内部署带宽充足, 直接从 hf-mirror.com 拉约 2-5 分钟(2.2GB)。
不依赖 hf-hub / optimum-cli, 避开 V2-A 已踩过的 hf-hub 0.3.2 redirect bug。

输出:
  /root/autodl-tmp/bge-reranker-v2-m3/
    config.json
    model.safetensors        (2.1 GB)
    tokenizer.json
    tokenizer_config.json
    sentencepiece.bpe.model
    special_tokens_map.json
    ...

用本机 docker-compose 起 TEI ORT backend 时需 optimum-cli 把 safetensors 转 ONNX,
而 Autodl GPU 直接吃 safetensors, 不需要转。
"""
from __future__ import annotations

import os
import sys
import urllib.request
from pathlib import Path

MODEL_ID = "BAAI/bge-reranker-v2-m3"
BASE = f"https://hf-mirror.com/{MODEL_ID}/resolve/main/"
OUT = Path(os.environ.get("MODEL_DIR", "/root/autodl-tmp/bge-reranker-v2-m3"))

# 必要文件(cross-encoder 加载需要的全集)
FILES = [
    "config.json",
    "model.safetensors",
    "tokenizer.json",
    "tokenizer_config.json",
    "sentencepiece.bpe.model",
    "special_tokens_map.json",
    "vocab.json",
]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    print(f"下载到: {OUT}")
    for f in FILES:
        dst = OUT / f
        if dst.exists() and dst.stat().st_size > 0:
            print(f"  ✓ {f} 已存在({dst.stat().st_size} bytes), 跳过")
            continue
        url = BASE + f
        print(f"  ⬇ {f}  ← {url}")
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "ragdoc/1.0"})
            with urllib.request.urlopen(req, timeout=900) as r, open(dst, "wb") as out:
                # 8K chunk + progress
                total = int(r.headers.get("Content-Length", 0))
                done = 0
                while True:
                    chunk = r.read(8192)
                    if not chunk:
                        break
                    out.write(chunk)
                    done += len(chunk)
                    if total:
                        sys.stdout.write(f"\r    {done*100//total}% ({done//1024}KB)")
                        sys.stdout.flush()
                print()
        except Exception as e:
            print(f"\nERROR: 下载 {f} 失败: {e}", file=sys.stderr)
            return 1
    print(f"\n✓ 模型全集下载完成: {OUT}")
    print(f"  跑: ./start_reranker.sh")
    return 0


if __name__ == "__main__":
    sys.exit(main())
