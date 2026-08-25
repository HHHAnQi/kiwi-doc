#!/usr/bin/env python3
"""
Agentic 复杂题冻结集生成器 v2(evidence-first, per protocol §7).
改进: 多 pattern 联合抽样扩大候选池 + 每 chunk 3 次重生成 + 递增 temperature.
"""
import hashlib, json, os, random, re, subprocess, sys, time
from pathlib import Path
import requests
from dotenv import load_dotenv

PROJECT = Path(__file__).resolve().parents[3]
load_dotenv(PROJECT / ".env", override=False)
OUT = PROJECT / "eval/agentic/datasets/agentic_complex_frozen.jsonl"
RETRIEVE_URL = os.getenv("RETRIEVE_URL", "http://localhost:8080/api/v1/retrieve")
TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")
GEN_URL = os.getenv("JUDGE_LLM_PROVIDER_1_BASE_URL", "https://api.deepseek.com/v1") + "/chat/completions"
GEN_KEY = os.getenv("JUDGE_LLM_PROVIDER_1_API_KEY", "")
GEN_MODEL = os.getenv("JUDGE_LLM_PROVIDER_1_MODEL", "deepseek-chat")
SOURCES = ["dubbo", "nacos", "seata", "rocketmq", "sentinel"]
N_PER_SLICE = int(os.getenv("N_PER_SLICE", "20"))
SEED = 42
MAX_RETRY = 3

def mysql_q(sql):
    r = subprocess.run(
        ["docker", "exec", "ragdoc-mysql", "sh", "-c",
         f'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" ragdoc -N -e "{sql}"'],
        capture_output=True, text=True)
    return r.stdout.strip()

def sample_pairs():
    pairs = []
    for i, s1 in enumerate(SOURCES):
        for s2 in SOURCES[i+1:]:
            r1 = mysql_q(f"SELECT c.id, c.document_id, LEFT(c.content,800) FROM chunks c JOIN documents d ON c.document_id=d.id WHERE d.source='{s1}' AND d.deleted_at IS NULL AND c.content LIKE '%配置%' AND CHAR_LENGTH(c.content) BETWEEN 200 AND 800 ORDER BY RAND({SEED}) LIMIT 4")
            r2 = mysql_q(f"SELECT c.id, c.document_id, LEFT(c.content,800) FROM chunks c JOIN documents d ON c.document_id=d.id WHERE d.source='{s2}' AND d.deleted_at IS NULL AND c.content LIKE '%配置%' AND CHAR_LENGTH(c.content) BETWEEN 200 AND 800 ORDER BY RAND({SEED}) LIMIT 4")
            for l1 in r1.split("\n"):
                for l2 in r2.split("\n"):
                    p1, p2 = l1.split("\t",2), l2.split("\t",2)
                    if len(p1)==3 and len(p2)==3:
                        pairs.append({"s1":s1,"c1":p1,"s2":s2,"c2":p2})
    random.seed(SEED); random.shuffle(pairs)
    return pairs

def sample_singles(patterns):
    like = " OR ".join(f"c.content LIKE '{p}'" for p in patterns)
    chunks = []
    for src in SOURCES:
        rows = mysql_q(f"SELECT c.id, c.document_id, LEFT(c.content,1000) FROM chunks c JOIN documents d ON c.document_id=d.id WHERE d.source='{src}' AND d.deleted_at IS NULL AND ({like}) AND CHAR_LENGTH(c.content) BETWEEN 200 AND 1000 ORDER BY RAND({SEED}) LIMIT 10")
        for l in rows.split("\n"):
            p = l.split("\t",2)
            if len(p)==3:
                chunks.append({"source":src,"chunk":p})
    random.seed(SEED); random.shuffle(chunks)
    return chunks

def gen_llm(prompt, temp=0.3):
    for _ in range(2):
        try:
            r = requests.post(GEN_URL, headers={"Authorization":f"Bearer {GEN_KEY}"},
                              json={"model":GEN_MODEL,"temperature":temp,
                                    "messages":[{"role":"user","content":prompt}]}, timeout=90)
            txt = r.json()["choices"][0]["message"]["content"].strip()
            m = re.search(r"\{.*\}", txt, re.S)
            if m: return json.loads(m.group(0))
        except Exception as e:
            print(f"  gen retry: {e}"); time.sleep(2)
    return None

PA = """基于以下两个组件的文档片段, 生成一道多文档比较题(JSON)。
组件1({s1}): {c1}
组件2({s2}): {c2}
规则: 问题需同时引用两组件信息; answer 只用片段内事实并标注来源组件; key_fact 为原文子串(10-25字)。
只输出 JSON: {{"question":"...","standard_answer":"...","required_facts":["..."],"key_fact":"...","multi_step_reason":"..."}}"""

PB = """基于以下文档片段, 生成一道多约束排障题(JSON)。
组件({source}): {chunk}
规则: 问题必须组合至少3个条件(现象/错误/版本/配置/部署/端口); 单次检索无法完整回答; key_fact 为原文子串。
只输出 JSON: {{"question":"...","standard_answer":"...","required_facts":["..."],"key_fact":"...","multi_step_reason":"..."}}"""

PC = """基于以下文档片段, 生成一道多步检索拼接题(JSON)。
组件({source}): {chunk}
规则: 问题需三层信息拼接(入口概念→具体配置→限制条件); 单次检索最多命中一层; key_fact 为原文子串。
只输出 JSON: {{"question":"...","standard_answer":"...","required_facts":["..."],"key_fact":"...","multi_step_reason":"..."}}"""

PS = """基于以下文档片段, 生成一道简单事实题(JSON)。
组件({source}): {chunk}
规则: 单组件单事实; Classic 单次检索可答; answer 简短(1-2句); key_fact 为原文子串。
只输出 JSON: {{"question":"...","standard_answer":"...","required_facts":["..."],"key_fact":"..."}}"""

def validate(case):
    kf = case.get("key_fact","")
    if not kf or len(kf)<6: return "key_fact 太短"
    esc = kf.replace("'","\\'").replace('"','\\"')
    if mysql_q(f"SELECT COUNT(*) FROM chunks WHERE content LIKE '%{esc}%'").split("\n")[0].strip()=="0":
        return f"key_fact 语料缺失"
    try:
        r = requests.post(RETRIEVE_URL, headers={"Authorization":f"Bearer {TOKEN}"},
                          json={"query":case["question"],"top_k":10}, timeout=30)
        if not r.json().get("items"): return "检索 0 召回"
    except Exception as e: return f"检索异常"
    q = case.get("question","")
    if len(q)<15 or len(q)>250: return f"长度异常 {len(q)}"
    if not case.get("required_facts"): return "required_facts 空"
    return None

def gen_slice(name, prompt_tpl, samples, formatter, n_target):
    cases = []
    for sample in samples:
        if len(cases) >= n_target: break
        for attempt in range(MAX_RETRY):
            prompt = prompt_tpl.format(**formatter(sample))
            case = gen_llm(prompt, temp=0.3 + attempt * 0.15)
            if not case: continue
            err = validate(case)
            if err:
                print(f"  [{name} r{attempt}] {err}")
                continue
            cases.append(case)
            print(f"  [{name} {len(cases)}/{n_target}] {case['question'][:50]}...")
            break
    return cases

def main():
    all_cases = []

    print("===== Slice A =====")
    pairs = sample_pairs()
    for c in gen_slice("A", PA, pairs, lambda p: {"s1":p["s1"],"c1":p["c1"][2],"s2":p["s2"],"c2":p["c2"][2]}, N_PER_SLICE):
        c.update(slice="A_multi_doc_compare", difficulty="hard", allow_refusal=False,
                evidence_sources=[p["s1"] for p in pairs[:1]] if not pairs else [])
        all_cases.append(c)

    print("===== Slice B =====")
    singles_b = sample_singles(["%错误%","%异常%","%故障%","%排查%","%解决%","%无法%","%失败%"])
    for c in gen_slice("B", PB, singles_b, lambda s: {"source":s["source"],"chunk":s["chunk"][2]}, N_PER_SLICE):
        c.update(slice="B_multi_constraint", difficulty="hard", allow_refusal=False)
        all_cases.append(c)

    print("===== Slice C =====")
    singles_c = sample_singles(["%配置%","%参数%","%设置%","%部署%","%安装%"])
    for c in gen_slice("C", PC, singles_c, lambda s: {"source":s["source"],"chunk":s["chunk"][2]}, N_PER_SLICE):
        c.update(slice="C_multi_step", difficulty="hard", allow_refusal=False)
        all_cases.append(c)

    print("===== Slice S =====")
    singles_s = sample_singles(["%默认%","%端口%","%协议%","%版本%"])
    for c in gen_slice("S", PS, singles_s, lambda s: {"source":s["source"],"chunk":s["chunk"][2]}, N_PER_SLICE):
        c.update(slice="S_simple_control", difficulty="easy", allow_refusal=False)
        all_cases.append(c)

    for i, c in enumerate(all_cases, 1):
        c["id"] = f"agentic_{c['slice'][0]}_{i:03d}"
    content = "\n".join(json.dumps(c, ensure_ascii=False) for c in all_cases)
    sha = hashlib.sha256(content.encode()).hexdigest()
    corpus_fp = mysql_q("SELECT COUNT(*), SUM(CRC32(content)) FROM chunks")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(content + "\n")
    slices = {}
    for c in all_cases:
        slices[c["slice"]] = slices.get(c["slice"], 0) + 1
    print(f"\n[done] {len(all_cases)} 题 → {OUT}")
    print(f"  SHA256: {sha}")
    print(f"  Corpus: {corpus_fp}")
    for k, v in sorted(slices.items()):
        print(f"  {k}: {v}")

if __name__ == "__main__":
    main()
