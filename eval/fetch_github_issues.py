#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V2-C Step 0 (P0): 从 GitHub Issues 抓取真实开发者问答, 作为评测集候选项。

为什么是 GitHub Issues 不是 Stack Overflow:
  SCA/Dubbo/Nacos 是中国开源生态, SO 上三标签总共 ~18 题带 accepted answer,
  不够做 200 题评测集。GitHub Issues 替代, 仅 question 类就:
    alibaba/nacos:                  745 kind/question
    alibaba/spring-cloud-alibaba:   258 kind/question
    apache/dubbo:                   377 type/bug + 真用户(assoc=NONE)
  远超需求, 且都是工程师真实提单。

选材标准 (避免抓到 maintainer 内部讨论/feature/PR):
  - 状态 closed
  - label: question 类 (Nacos/SCA=kind/question) 或 bug 类(Dubbo:type/bug)
  - 提交者 author_association = NONE (即外部真实用户, 非 maintainer/ contributor)
  - 评论数 >= 2 (有维护者回答的瑕疵才有 ground truth)
  - 排除 good-first-issue / contribution-welcome / 标题含 Backport/PR

Ground truth (G1) 策略:
  issue title+body 作 question.
  ground_truth_answer 取该 issue 下 author_association ∈ {OWNER, MEMBER, COLLABORATOR}
  的维护者评论 (挑最长且含代码/非模板的那条)。

输出: eval/issues_raw.jsonl
  每行: {
    repo, issue_number, html_url, title, labels,
    question,                            # title + body(前 1000 字)
    author_login, author_association,    # 提问者(应为 NONE)
    maintainer_login, maintainer_association,
    ground_truth_answer,                 # 该 issue 下维护者最具信息量的评论
    source  # "github-issue"
  }

用法:
  python3 eval/fetch_github_issues.py            # 抓 400 条
  python3 eval/fetch_github_issues.py 30         # 调试: 抓 30 条
  GITHUB_TOKEN=ghp_xxx python3 ...               # 带 token 提速 60→5000/h

依赖:
  pip install requests
"""

import json
import os
import sys
import time
from pathlib import Path

import requests

OUT_FILE = Path(__file__).resolve().parent / "issues_raw.jsonl"

REPOS = [
    # (repo, query 标签过滤策略)
    # Nacos/SCA 用 kind/question; Dubbo 没有 question 标签, 借 type/bug + 排除纯 maintainer 内部
    ("alibaba/nacos", "label:kind/question"),
    ("alibaba/spring-cloud-alibaba", "label:kind/question"),
    ("apache/dubbo", "label:type/bug"),  # Dubbo 的真用户困境多归到 bug
]

# 维护者身份 (GitHub author_association 字段取值)
# OWNER: 仓库所有人, MEMBER: 组织内成员(通常 committer), COLLABORATOR: 被邀请的协作者
# CONTRIBUTOR 只代表提过 PR, 权威性不够, 不算维护者
MAINTAINER_ASSOC = {"OWNER", "MEMBER", "COLLABORATOR"}

GITHUB_TOKEN = os.getenv("GITHUB_TOKEN", "")
HEADERS = {
    "Accept": "application/vnd.github+json",
}
if GITHUB_TOKEN:
    HEADERS["Authorization"] = f"Bearer {GITHUB_TOKEN}"


def gh_get(url, params=None, max_retry=3):
    """带速率限制退避的 GET。"""
    for attempt in range(max_retry):
        r = requests.get(url, headers=HEADERS, params=params, timeout=30)
        if r.status_code == 200:
            # 检查剩余配额
            remaining = r.headers.get("X-RateLimit-Remaining")
            if remaining is not None and int(remaining) < 10:
                reset = int(r.headers.get("X-RateLimit-Reset", 0))
                wait = max(1, reset - int(time.time()))
                print(f"  [warn] 配额将尽({remaining}), 等 {wait}s 重置")
                time.sleep(wait)
            return r.json()
        if r.status_code == 403 and "rate limit" in r.text.lower():
            reset = int(r.headers.get("X-RateLimit-Reset", 0))
            wait = max(1, reset - int(time.time()))
            print(f"  [warn] 触发 rate limit, 等 {wait}s")
            time.sleep(min(wait, 3600))  # 最多等 1 小时
            continue
        if r.status_code == 404:
            return None
        print(f"  [warn] GH {r.status_code}: {r.text[:120]}")
        time.sleep(2 * (attempt + 1))
    return None


def pick_maintainer_comment(comments):
    """从 issue 评论列表里挑一条最有信息量的维护者评论。

    排除: 纯表情 / 纯引用 / 过短 (<30 字符) / 模板化回复
    优先: 信息量大 (中等偏长) 的本体性回答
    """
    candidates = [
        c for c in comments
        if c.get("author_association") in MAINTAINER_ASSOC
    ]
    if not candidates:
        return None

    def score(c):
        body = (c.get("body") or "").strip()
        if len(body) < 30:
            return -1
        # 排除常见模板/无信息回复
        low = body.lower()
        if any(k in low for k in (
            "duplicate of", "closed as", "please provide", "wait for feedback",
            "+1", "verified", "fixed in", "released in",
        )):
            return -1
        # 中等长度(80-1500)打高分, 太长(可能贴日志)或太短打折
        length_score = 1.0 if 80 <= len(body) <= 1500 else 0.5
        # 是否含代码块/配置项 (技术实质)
        has_code = "```" in body or "`" in body
        return len(body) * length_score + (300 if has_code else 0)

    best = max(candidates, key=score)
    if score(best) < 0:
        return None
    return best


def clean_question(title, body):
    """清洗 issue 成问题描述。"""
    text = (title or "").strip()
    if body:
        # body 太长截前 1000 字, 去掉 markdown 表情符号积累
        b = body.strip()[:1000]
        text = text + "\n\n" + b
    return text


def fetch_for_repo(repo, label_filter, target_n):
    """用 search API 精筛真用户 issue, 再逐条拉 comments 找维护者评论。

    筛选收紧:
      - 状态 closed, 评论 >= 2 (有维护者回答瑕疵)
      - author_association=NONE (外部真用户提单, 排除 maintainer/contributor 内部讨论)
      - label 过滤 (question / bug 类)
      - 排除 good-first-issue / contribution-welcome / Backport 标题
    """
    results = []
    page = 1
    seen = 0
    while len(results) < target_n and page <= 10 and seen < target_n * 5:
        # GitHub search query 拼接 (注意空格用 +, repo 里 / 不用编码)
        q = (
            f"repo:{repo} is:issue is:closed {label_filter} "
            f"comments:>=2 author_association:none"
        )
        params = {
            "q": q,
            "sort": "comments",  # 评论多的优先 (讨论充分, 多有维护者回答)
            "order": "desc",
            "per_page": 50,
            "page": page,
        }
        data = gh_get("https://api.github.com/search/issues", params=params)
        if not data or not data.get("items"):
            break
        seen += len(data["items"])

        for it in data["items"]:
            if "pull_request" in it:
                continue
            body = it.get("body") or ""
            title = it.get("title") or ""
            if len(body.strip()) < 30:
                continue
            # 排除内部维护性 issue (backport/PR 描述/contribution 招募)
            low_title = title.lower()
            labels = [l["name"] for l in it.get("labels", [])]
            skip_keywords = ("backport", "[backport]", "pull request")
            skip_labels = {"good first issue", "contribution welcome", "help wanted"}
            if any(k in low_title for k in skip_keywords):
                continue
            if skip_labels & set(labels):
                continue

            # 拉 comments 找维护者实质性回答
            comments = gh_get(it["comments_url"]) or []
            mc = pick_maintainer_comment(comments)
            if mc is None:
                continue

            results.append({
                "repo": repo,
                "issue_number": it["number"],
                "html_url": it["html_url"],
                "title": title,
                "labels": labels[:8],
                "question": clean_question(title, body),
                "author_login": it["user"]["login"],
                "author_association": it.get("author_association"),
                "maintainer_login": mc["user"]["login"],
                "maintainer_association": mc["author_association"],
                "ground_truth_answer": (mc.get("body") or "").strip()[:2000],
                "source": "github-issue",
            })
            print(f"  ✓ [{repo}] #{it['number']} {title[:50]}  (got {len(results)}/{target_n})")
            if len(results) >= target_n:
                break
        page += 1
    return results


def main():
    total_target = int(sys.argv[1]) if len(sys.argv) > 1 else 400
    # 三 repo 分配: Nacos/SCA 储量充足(question 类), Dubbo 借 bug 类
    per_repo = {
        "alibaba/nacos": int(total_target * 0.40),
        "alibaba/spring-cloud-alibaba": int(total_target * 0.30),
        "apache/dubbo": total_target - int(total_target * 0.40) - int(total_target * 0.30),
    }

    print(f"[start] 目标 {total_target} 条, 分布: {per_repo}")
    if GITHUB_TOKEN:
        print(f"  带 GITHUB_TOKEN (配额 5000/h)")
    else:
        print(f"  无 GITHUB_TOKEN (配额 60/h, 可能很慢; export GITHUB_TOKEN=ghp_xxx 提速)")

    all_items = []
    for repo, label_filter in REPOS:
        n = per_repo[repo]
        print(f"\n=== {repo} (目标 {n}, filter='{label_filter}') ===")
        items = fetch_for_repo(repo, label_filter, n)
        all_items.extend(items)
        print(f"  [{repo}] 实际拿到 {len(items)}/{n}")

    with open(OUT_FILE, "w", encoding="utf-8") as f:
        for it in all_items:
            f.write(json.dumps(it, ensure_ascii=False) + "\n")

    print(f"\n✓ 写入 {len(all_items)} 条到 {OUT_FILE}")
    print(f"  分布: " + ", ".join(
        f"{r}={sum(1 for x in all_items if x['repo']==r)}" for r, _ in REPOS
    ))
    print(f"  下一步: 人工筛选 ~200 条, 再用 embedding 反查 ground_truth_chunk_id")


if __name__ == "__main__":
    main()
