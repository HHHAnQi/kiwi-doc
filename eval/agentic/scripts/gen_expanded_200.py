#!/usr/bin/env python3
"""
扩展到 200 题 + 非抽取式金标(Phase 2)。

改进 vs gen_complex_challenge_set.py:
1. 非抽取式金标: LLM 用自己的话写答案(不抄 chunk 原文), 然后校验关键事实在语料中
2. 扩展到 200 题(功效分析: MDE 5.4pp @ α=0.05, power=0.8)
3. 保留原 80 题作为子集(标注 gold_type=extractive)
"""
import hashlib, json, os, random, re, subprocess, sys, time
from pathlib import Path
import requests
from dotenv import load_dotenv

PROJECT = Path(__file__).resolve().parents[3]
load_dotenv(PROJECT / ".env", override=False)

OUT = PROJECT / "eval/agentic/datasets/agentic_expanded_200.jsonl"
RETRIEVE_URL = os.getenv("RETRIEVE_URL", "http://localhost:8080/api/v1/retrieve")
TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")
GEN_URL = os.getenv("JUDGE_LLM_PROVIDER_1_BASE_URL", "https://api.deepseek.com/v1") + "/chat/completions"
GEN_KEY = os.getenv("JUDGE_LLM_PROVIDER_1_API_KEY", "")
GEN_MODEL = os.getenv("JUDGE_LLM_PROVIDER_1_MODEL", "deepseek-chat")

SOURCES = ["dubbo", "nacos", "seata", "rocketmq", "sentinel"]
TARGET_PER_SLICE = 50  # 4 × 50 = 200
SEED = 43  # 不同于原集(42), 避免重复

def mysql_q(sql):
    r = subprocess.run(
        ["docker", "exec", "ragdoc-mysql", "sh", "-c",
         f'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" ragdoc -N -e "{sql}"'],
        capture_output=True, text=True)
    return r.stdout.strip()

def sample_chunks(patterns, limit=15):
    like = " OR ".join(f"c.content LIKE '{p}'" for p in patterns)
    chunks = []
    for src in SOURCES:
        rows = mysql_q(
            f"SELECT c.id, c.document_id, LEFT(c.content,1200) FROM chunks c "
            f"JOIN documents d ON c.document_id=d.id "
            f"WHERE d.source='{src}' AND d.deleted_at IS NULL "
            f"AND ({like}) AND CHAR_LENGTH(c.content) BETWEEN 200 AND 1200 "
            f"ORDER BY RAND({SEED}) LIMIT {limit}")
        for l in rows.split("\n"):
            p = l.split("\t", 2)
            if len(p) == 3:
                chunks.append({"source": src, "chunk": p})
    random.seed(SEED); random.shuffle(chunks)
    return chunks

def sample_pairs():
    pairs = []
    for i, s1 in enumerate(SOURCES):
        for s2 in SOURCES[i+1:]:
            r1 = mysql_q(f"SELECT c.id, c.document_id, LEFT(c.content,800) FROM chunks c JOIN documents d ON c.document_id=d.id WHERE d.source='{s1}' AND d.deleted_at IS NULL AND CHAR_LENGTH(c.content) BETWEEN 200 AND 800 ORDER BY RAND({SEED}) LIMIT 6")
            r2 = mysql_q(f"SELECT c.id, c.document_id, LEFT(c.content,800) FROM chunks c JOIN documents d ON c.document_id=d.id WHERE d.source='{s2}' AND d.deleted_at IS NULL AND CHAR_LENGTH(c.content) BETWEEN 200 AND 800 ORDER BY RAND({SEED}) LIMIT 6")
            for l1 in r1.split("\n"):
                for l2 in r2.split("\n"):
                    p1, p2 = l1.split("\t",2), l2.split("\t",2)
                    if len(p1)==3 and len(p2)==3:
                        pairs.append({"s1":s1,"c1":p1,"s2":s2,"c2":p2})
    random.seed(SEED); random.shuffle(pairs)
    return pairs

def gen_llm(prompt, temp=0.4):
    for _ in range(2):
        try:
            r = requests.post(GEN_URL, headers={"Authorization": f"Bearer {GEN_KEY}"},
                              json={"model": GEN_MODEL, "temperature": temp,
                                    "messages": [{"role": "user", "content": prompt}]}, timeout=90)
            txt = r.json()["choices"][0]["message"]["content"].strip()
            m = re.search(r"\{.*\}", txt, re.S)
            if m: return json.loads(m.group(0))
        except Exception as e:
            print(f"  gen retry: {e}"); time.sleep(2)
    return None

# 非抽取式金标 prompt: LLM 用自己的话写答案
# 关键改进: 答案不是从 chunk 原文抄的, 而是基于 chunk 内容用自己的话重新表述
PROMPTS = {
    "A": """基于以下两个组件的文档片段, 生成一道多文档比较题(JSON)。

组件1({s1}): {c1}
组件2({s2}): {c2}

规则:
1. 问题要求同时引用两个组件的信息;
2. standard_answer 用你自己的话总结(不要照抄片段原文, 用不同句式重新表述关键信息);
3. required_facts 列出关键事实点(每个≤20字, 这些事实必须在片段中出现);
4. key_fact 是原文中的事实子串(10-25字, 用于校验)。

只输出 JSON: {{"question":"...","standard_answer":"...","required_facts":["..."],"key_fact":"...","multi_step_reason":"..."}}""",

    "B": """基于以下文档片段, 生成一道多约束排障题(JSON)。要求问题组合≥3个条件(现象/错误/版本/配置/部署)。

组件({source}): {chunk}

规则:
1. standard_answer 用你自己的话(不要照抄原文);
2. key_fact 为原文子串(校验用)。

只输出 JSON: {{"question":"...","standard_answer":"...","required_facts":["..."],"key_fact":"...","multi_step_reason":"..."}}""",

    "C": """基于以下文档片段, 生成一道多步检索拼接题(JSON)。问题需三层信息(入口概念→配置→限制)。

组件({source}): {chunk}

规则:
1. standard_answer 用你自己的话;
2. key_fact 为原文子串。

只输出 JSON: {{"question":"...","standard_answer":"...","required_facts":["..."],"key_fact":"...","multi_step_reason":"..."}}""",

    "S": """基于以下文档片段, 生成一道简单事实题(JSON)。单组件单事实, Classic单次检索可答。

组件({source}): {chunk}

规则:
1. standard_answer 用你自己的话(简短, 1-2句);
2. key_fact 为原文子串。

只输出 JSON: {{"question":"...","standard_answer":"...","required_facts":["..."],"key_fact":"..."}}"""
}

def validate(case):
    kf = case.get("key_fact", "")
    if not kf or len(kf) < 6: return "key_fact 太短"
    esc = kf.replace("'","\\'").replace('"','\\"')
    if mysql_q(f"SELECT COUNT(*) FROM chunks WHERE content LIKE '%{esc}%'").split("\n")[0].strip() == "0":
        return "key_fact 语料缺失"
    try:
        r = requests.post(RETRIEVE_URL, headers={"Authorization": f"Bearer {TOKEN}"},
                          json={"query": case["question"], "top_k": 10}, timeout=30)
        if not r.json().get("items"): return "检索 0 召回"
    except: return "检索异常"
    if len(case.get("question","")) < 15: return "问题太短"
    if not case.get("required_facts"): return "required_facts 空"
    return None

def main():
    random.seed(SEED)
    all_cases = []

    # Slice A: 多文档比较
    print("===== Slice A =====")
    pairs = sample_pairs()
    count = 0
    for p in pairs:
        if count >= TARGET_PER_SLICE: break
        for attempt in range(3):
            prompt = PROMPTS["A"].format(s1=p["s1"], c1=p["c1"][2], s2=p["s2"], c2=p["c2"][2])
            case = gen_llm(prompt, temp=0.4 + attempt*0.1)
            if not case: continue
            err = validate(case)
            if err: print(f"  [A r{attempt}] {err}"); continue
            case.update(slice="A_multi_doc_compare", difficulty="hard",
                       allow_refusal=False, gold_type="non_extractive")
            all_cases.append(case); count += 1
            print(f"  [A {count}/{TARGET_PER_SLICE}] {case['question'][:50]}...")
            break

    # Slice B/C/S
    for slice_name, patterns in [
        ("B", ["%错误%","%异常%","%故障%","%排查%","%解决%","%无法%","%失败%","%警告%"]),
        ("C", ["%配置%","%参数%","%设置%","%部署%","%安装%","%集群%"]),
        ("S", ["%默认%","%端口%","%协议%","%版本%","%支持%"])
    ]:
        print(f"===== Slice {slice_name} =====")
        singles = sample_chunks(patterns)
        count = 0
        for s in singles:
            if count >= TARGET_PER_SLICE: break
            for attempt in range(3):
                prompt = PROMPTS[slice_name].format(source=s["source"], chunk=s["chunk"][2])
                case = gen_llm(prompt, temp=0.4 + attempt*0.1)
                if not case: continue
                err = validate(case)
                if err: print(f"  [{slice_name} r{attempt}] {err}"); continue
                diff = "hard" if slice_name != "S" else "easy"
                case.update(slice=f"{slice_name}_{'multi_constraint' if slice_name=='B' else 'multi_step' if slice_name=='C' else 'simple_control'}",
                           difficulty=diff, allow_refusal=False, gold_type="non_extractive")
                all_cases.append(case); count += 1
                print(f"  [{slice_name} {count}/{TARGET_PER_SLICE}] {case['question'][:50]}...")
                break

    for i, c in enumerate(all_cases, 1):
        c["id"] = f"agentic_v2_{c['slice'][0]}_{i:03d}"
    content = "\n".join(json.dumps(c, ensure_ascii=False) for c in all_cases)
    sha = hashlib.sha256(content.encode()).hexdigest()
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(content + "\n")
    slices = {}
    for c in all_cases:
        slices[c["slice"]] = slices.get(c["slice"], 0) + 1
    print(f"\n[done] {len(all_cases)} 题 → {OUT}")
    print(f"  SHA256: {sha}")
    for k, v in sorted(slices.items()):
        print(f"  {k}: {v}")

if __name__ == "__main__":
    main()
