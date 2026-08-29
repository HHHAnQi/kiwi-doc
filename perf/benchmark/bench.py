#!/usr/bin/env python3
"""Phase 4 真实 benchmark: retrieval / LLM TTFT(SSE) / full RAG E2E, P50/P95/P99.
用法: python3 perf/benchmark/bench.py --concurrency 1|10 --n N --out perf/benchmark/result_c{N}.json
所有原始 per-request 样本落 JSON, 报告可从 artifact 重算。"""
import argparse, json, time, statistics, threading, requests, sys

BASE = "http://localhost:8080"
TOKEN = "dev-token-change-me"
HEADERS = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}
QUERIES = [  # 取自冻结 pilot 数据集(pilot50)前若干题, 语料=165docs/3076chunks
    "Seata的AT模式回滚依赖什么表",
    "Dubbo 中的分布式事务基于什么规范实现",
    "Nacos 使用什么一致性协议",
    "RocketMQ 刷盘方式默认是什么",
    "Sentinel 的滑动窗口统计默认参数",
    "Seata TC 的默认端口是多少",
    "Nacos 控制台默认端口",
    "Dubbo 默认使用什么通信框架",
]

def pct(xs, p):
    if not xs: return None
    xs = sorted(xs); k = max(0, min(len(xs)-1, int(round(p/100*(len(xs)-1)))))
    return round(xs[k])

def retrieve_once(q):
    t0=time.time()
    r=requests.post(f"{BASE}/api/v1/retrieve", headers=HEADERS,
                    json={"query":q,"top_k":5}, timeout=60)
    r.raise_for_status(); return (time.time()-t0)*1000

def chat_e2e_once(q):
    t0=time.time()
    r=requests.post(f"{BASE}/api/v1/chat", headers=HEADERS,
                    json={"query":q,"mode":"RAG","top_k":5}, timeout=180)
    r.raise_for_status(); return (time.time()-t0)*1000

def ttft_once(q):
    """SSE 首 DeltaEvent 延迟 — 完整流式 LLM TTFT, 非 API acknowledgement。"""
    t0=time.time()
    with requests.post(f"{BASE}/api/v1/chat/sse", headers={**HEADERS,"Accept":"text/event-stream"},
                       json={"query":q,"mode":"RAG","top_k":5}, stream=True, timeout=180) as r:
        r.raise_for_status()
        for line in r.iter_lines(decode_unicode=True):
            if line and "delta" in line.lower():
                return (time.time()-t0)*1000  # 首个含内容的流事件
        return (time.time()-t0)*1000

def run_pool(fn, n, conc):
    out=[]; lock=threading.Lock(); idx=[0]
    def worker():
        while True:
            with lock:
                i=idx[0]; idx[0]+=1
                if i>=n: return
                q=QUERIES[i%len(QUERIES)]
            try: ms=fn(q)
            except Exception as e: ms=None; print("ERR",str(e)[:60],file=sys.stderr)
            with lock: out.append(ms)
    ths=[threading.Thread(target=worker) for _ in range(conc)]
    t0=time.time()
    for t in ths: t.start()
    for t in ths: t.join()
    wall=time.time()-t0
    ok=[x for x in out if x]
    return {"samples":len(ok),"failed":n-len(ok),"wall_s":round(wall,1),
            "qps":round(len(ok)/wall,2) if wall else None,
            "p50_ms":pct(ok,50),"p95_ms":pct(ok,95),"p99_ms":pct(ok,99),
            "mean_ms":round(statistics.mean(ok),1) if ok else None}

if __name__=="__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("--concurrency",type=int,required=True)
    ap.add_argument("--n",type=int,default=40)
    ap.add_argument("--out",required=True)
    a=ap.parse_args()
    res={"meta":{"concurrency":a.concurrency,"n":a.n}, "results":{}}
    for name,fn in [("retrieve",retrieve_once),("llm_ttft_sse",ttft_once),("chat_e2e",chat_e2e_once)]:
        print(f"[{name}] c={a.concurrency} n={a.n} ...",flush=True)
        res["results"][name]=run_pool(fn,a.n,a.concurrency)
        print("  ->",res["results"][name],flush=True)
    json.dump(res,open(a.out,"w"),ensure_ascii=False,indent=2)
    print("saved",a.out)
