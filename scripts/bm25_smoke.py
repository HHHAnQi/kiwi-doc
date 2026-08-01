#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C 方案冒烟实验: 验证 Milvus 2.5.0 原生 BM25 Function + 中文分词是否真能跑。

不动现有 collection, 只建临时 bm25_smoke collection 测:
  1. schema 带 VARCHAR 字段 + SPARSE_FLOAT_VECTOR 字段 + Function(BM25)
  2. 插 3 条中文 chunk
  3. 跑 BM25 sparse 查询, 看能否命中目标 chunk
  4. 关键: 中文分词(默认空格分词对中文无效), 验证 jieba analyzer

成功 → C 方案走通, 后续 Java 端仿照此 schema 改 collection
失败 → 看 analyzer 是否需要显式 jieba 配置
"""
import os
from pymilvus import MilvusClient, DataType, Function, FunctionType

COLL = "bm25_smoke"
URI = f"http://{os.getenv('MILVUS_HOST','localhost')}:{os.getenv('MILVUS_PORT','19530')}"

client = MilvusClient(uri=URI)

# 删旧(幂等)
if client.has_collection(COLL):
    client.drop_collection(COLL)

# Schema: text 字段 + BM25 函数自动算 sparse
schema = client.create_schema(auto_id=True, enable_dynamic_field=False)
schema.add_field("id", DataType.INT64, is_primary=True, auto_id=True)
schema.add_field("text", DataType.VARCHAR, max_length=2000,
                 enable_analyzer=True, analyzer_params={"type": "chinese"})
schema.add_field("sparse_bm25", DataType.SPARSE_FLOAT_VECTOR, max_length=1000)
schema.add_field("dense_vector", DataType.FLOAT_VECTOR, dim=128)  # 占位, 不用真 embed

# BM25 Function: 输入 text, 输出 sparse_bm25
schema.add_function(Function(
    name="text_to_bm25",
    function_type=FunctionType.BM25,
    input_field_names=["text"],
    output_field_names=["sparse_bm25"],
))

# 索引
index_params = client.prepare_index_params()
index_params.add_index(field_name="sparse_bm25", index_type="SPARSE_INVERTED_INDEX",
                       metric_type="BM25")
index_params.add_index(field_name="dense_vector", index_type="FLAT", metric_type="COSINE")

client.create_collection(
    collection_name=COLL,
    schema=schema,
    index_params=index_params,
)
print(f"✓ collection '{COLL}' 创建成功(Milvus 2.5 BM25 Function schema)")

# 插 3 条中文 chunk (用真实的 SCA 文档片段)
data = [
    {"text": "Nacos 配置改了不生效, 需要配合 @RefreshScope 注解, 配置变更时 bean 才会重建", "dense_vector": [0.1]*128},
    {"text": "Dubbo 默认负载均衡算法是 random 随机调用, 通过 loadbalance 参数可改为 consistenthash", "dense_vector": [0.2]*128},
    {"text": "Dubbo 异步调用 provider 返回 CompletableFuture, 框架自动识别为异步执行", "dense_vector": [0.3]*128},
]
client.insert(COLL, data)
print(f"✓ 插入 {len(data)} 条中文 chunk")

# 关键: BM25 search —— 看"负载均衡" 能命中第二条 Dubbo 而不是第一条 Nacos
client.load_collection(COLL)
import time
time.sleep(2)  # 给索引构建/数据可见一点时间
print(f"✓ load 完成")

# BM25 sparse 搜索
results = client.search(
    COLL,
    data=["Dubbo 默认负载均衡"],
    anns_field="sparse_bm25",  # ← 关键: 用 sparse 字段搜
    limit=3,
    output_fields=["text"],
    search_params={"params": {"drop_ratio_search": 0.2}},
)
print("\n=== BM25 搜索 'Dubbo 默认负载均衡' 结果(期望 top1 = Dubbo LB chunk)===")
print(f"  raw 结果数: {len(results[0])}")
for hit in results[0]:
    text = hit["entity"]["text"][:60]
    score = hit["distance"]
    print(f"  score={score:.4f}  text={text}")

# 第二个 query 测中文关键词
results2 = client.search(
    COLL,
    data=["Nacos 配置不刷新"],
    anns_field="sparse_bm25",
    limit=3,
    output_fields=["text"],
)
print("\n=== BM25 搜索 'Nacos 配置不刷新' (期望 top1 = Nacos RefreshScope chunk)===")
for hit in results2[0]:
    text = hit["entity"]["text"][:60]
    print(f"  score={hit['distance']:.4f}  text={text}")

# 清理
client.drop_collection(COLL)
print("\n✓ 冒烟实验完成, 已清理临时 collection")
