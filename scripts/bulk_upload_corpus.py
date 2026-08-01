#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扩充知识库: 把 SCA 五大组件 markdown 文档批量上传到 rag-doc-platform。

设计:
  - 走正规 POST /api/v1/documents, 触发完整生产链路(Tika→TextCleaner→chunking→embed→Milvus)
  - 同时验证链路稳定性, 不绕过任何清洗/embedding 步骤
  - 限速并发, 避免压垮 BGE-M3 embedding 服务(amd64 Rosetta 模拟下慢)

用法:
  cd /Users/huanqi/RagDoc/rag-doc-platform
  .venv/bin/python3 scripts/bulk_upload_corpus.py
  # 或指定目录
  CORPUS_DIR=/path/to/corpus MAX_FILES=500 .venv/bin/python3 scripts/bulk_upload_corpus.py
"""
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests
from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(PROJECT_ROOT / ".env", override=False)

UPLOAD_URL = os.getenv("UPLOAD_URL", "http://localhost:8092/api/v1/documents")
AUTH_TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")
CORPUS_DIR = os.getenv(
    "CORPUS_DIR",
    "/Users/huanqi/RagDoc/testdata/sca-corpus",
)
# 每个组件最多文件数(避免单组件垄断语料)。方向 C: 4 大组件各 40 + sentinel ~30 凑数。
MAX_PER_SOURCE = int(os.getenv("MAX_PER_SOURCE", "40"))
# 并发上传数。BGE-M3 在 Rosetta amd64 模拟下并发 >=2 会触发 90s 超时(TimeoutException),
# 经验稳定值是 1(串行), 让 embed 服务专心处理当前请求。200 文件总耗时约 30-40 分钟。
MAX_WORKERS = int(os.getenv("MAX_WORKERS", "1"))
# 单文件最大重试次数(embed 偶发超时, 重试可救回)
MAX_RETRIES = int(os.getenv("MAX_RETRIES", "2"))
# 文件最小字符(过滤纯脚手架 README)
MIN_CHARS = int(os.getenv("MIN_CHARS", "500"))

# 路径包含这些"完整片段"的 md 跳过(脚手架垃圾, 并非文档)。用路径段 split('/') 后集合匹配,
# 不会误杀 "xxx.github.io" 这种含 ".github" 的合法域名目录名。
SKIP_PATH_PARTS = (".github", "ISSUE_TEMPLATE", "node_modules", "vendor")

# 各组件文档目录 + 业务元数据(source/version/language/doc_type)。
# 实测路径(对照前一轮"路径错配"教训, 全部先 ls 验证过):
#   - dubbo 191 / nacos 62 / seata 1094 / rocketmq 69 个 ≥500c md
#   - sentinel 仅 52 个英文模块 README(中文文档站 sentinelguard.io 未克隆, 二期 clone 后回灌)
SOURCES = [
    # (source_name, rel_dir, version, language, doc_type)
    ("dubbo",    "dubbo-website/content/zh-cn/docs",                                   "3.0", "zh", "doc"),
    ("nacos",    "nacos-group.github.io/src/content/docs/v2.4/zh-cn",                  "2.4", "zh", "doc"),
    ("seata",    "incubator-seata-website/i18n/zh-cn/docusaurus-plugin-content-docs",  "2.0", "zh", "doc"),
    ("rocketmq", "rocketmq-site/versioned_docs/version-5.0",                            "5.0", "zh", "doc"),
    # Sentinel 二期: 本地 corpus 只有英文模块 README, 来源标英语 + demo 类型
    ("sentinel", "Sentinel", "1.8", "en", "demo"),
]


def list_md_files():
    """枚举每个 source 下不超过 MAX_PER_SOURCE 个 md 文件, 跳过过短, 带出业务元数据。"""
    out = []
    for src_name, rel_dir, version, lang, doc_type in SOURCES:
        d = Path(CORPUS_DIR) / rel_dir
        if not d.exists():
            print(f"  [skip] {src_name}: {d} 不存在")
            continue
        # Sentinel 直接 rglob 整个仓库收 md; 其他组件用各自 docs 子目录
        files = sorted(d.rglob("*.md"))
        picked = 0
        for f in files:
            if picked >= MAX_PER_SOURCE:
                break
            try:
                # 跳过脚手架/issue 模板/test 路径。
                # 必须按路径段匹配( split by / ), 否则 ".github" 会误杀 "xxx.github.io" 这类目录
                uniform_path = str(f).replace("\\", "/")
                norm = uniform_path.lower()
                parts = {p.lower() for p in uniform_path.split("/")}
                if any(part in parts for part in SKIP_PATH_PARTS) or "/test/" in norm or "/tests/" in norm:
                    continue
                size = f.stat().st_size
                if size < MIN_CHARS:
                    continue
                # 元数据随文件记录, 与上传 API 对齐
                out.append((src_name, f, version, lang, doc_type))
                picked += 1
            except OSError:
                continue
        print(f"  [{src_name}] 入选 {picked}/{len(files)} 个 md (dir={rel_dir}, version={version})")
    return out


def upload_one(src_name, path, version, language, doc_type):
    """上传一个 md 文件 + 业务元数据。文件名带 source 前缀避免冲突。
    内置重试(MAX_RETRIES 次), embed 服务偶发超时可救回。
    """
    filename = f"{src_name}-{path.stem[:40]}.md"
    last_err = None
    for attempt in range(1, MAX_RETRIES + 2):  # 初试 + 重试
        try:
            with open(path, "rb") as fp:
                files = {"file": (filename, fp, "text/markdown")}
                data = {"source": src_name, "version": version, "language": language, "doc_type": doc_type}
                headers = {"Authorization": f"Bearer {AUTH_TOKEN}"}
                # 超时给 120s, 给单文件完整链路(Tika→chunk→embed→Milvus) 充裕空间
                r = requests.post(UPLOAD_URL, files=files, data=data, headers=headers, timeout=120)
            if r.status_code in (200, 201):
                body = r.json()
                doc_id = body.get("document_id") or body.get("id")
                return {"ok": True, "doc_id": doc_id, "filename": filename}
            # 4xx 通常重试无益, 直接返回失败
            last_err = f"http {r.status_code}: {r.text[:80]}"
            if 400 <= r.status_code < 500:
                return {"ok": False, "filename": filename, "status": r.status_code, "err": last_err}
        except Exception as e:
            last_err = str(e)[:120]
        if attempt <= MAX_RETRIES:
            time.sleep(3)  # 重试前等 3s 让 embed 服务喘口气
    return {"ok": False, "filename": filename, "err": last_err or "unknown"}


def _interleave_by_source(files):
    """把按 source 顺序聚集的 files 打乱成 round-robin: d1,n1,s1,r1,se1, d2,n2,...
    防止单 source(dubbo) 在并发线程池里先批量占满, 把其他 source 饿死数分钟。
    """
    buckets = {}
    for s, p, v, l, dt in files:
        buckets.setdefault(s, []).append((s, p, v, l, dt))
    max_len = max(len(b) for b in buckets.values()) if buckets else 0
    out = []
    for i in range(max_len):
        for s in buckets:  # dict 保持 SOURCES 顺序
            if i < len(buckets[s]):
                out.append(buckets[s][i])
    return out


def main():
    print(f"[1/2] 枚举 corpus 文件 (corpus_dir={CORPUS_DIR}, max_per_source={MAX_PER_SOURCE}, min_chars={MIN_CHARS})")
    files = list_md_files()
    total = len(files)
    print(f"\n  入选总数: {total} 个 md 文件\n")

    if not files:
        print("✗ 无文件可上传")
        sys.exit(1)

    # round-robin 交错, 保证 5 个 source 同步推进, 避免单 source 占满线程池
    files = _interleave_by_source(files)

    print(f"[2/2] 上传 (并发 {MAX_WORKERS}, round-robin 5 组件均衡, 走完整 Tika→chunking→embed→Milvus 链路)\n")
    ok_count = 0
    fail_count = 0
    t0 = time.time()

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as ex:
        futures = {ex.submit(upload_one, s, p, v, l, dt): (s, p) for s, p, v, l, dt in files}
        for i, fut in enumerate(as_completed(futures), 1):
            res = fut.result()
            if res["ok"]:
                ok_count += 1
                tag = "✓"
            else:
                fail_count += 1
                tag = "✗"
            if i % 10 == 0 or i == total:
                elapsed = time.time() - t0
                rate = ok_count / elapsed if elapsed else 0
                print(f"  [{i}/{total}] {tag} {res['filename'][:50]:50} ok={ok_count} fail={fail_count} rate={rate:.1f}/s")

    print(f"\n✓ 完成: 成功 {ok_count}/{total}, 失败 {fail_count}")
    print(f"  耗时 {time.time() - t0:.0f}s")
    print(f"\n下一步: 等所有文档 READY 后跑 eval-set-baseline")


if __name__ == "__main__":
    main()
