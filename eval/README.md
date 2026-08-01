# V2-C 评测目录

本目录负责 V2-C 评测体系, 包含三个核心脚本:

| 脚本 | 作用 |
|---|---|
| `gen_questions.py N` | 调 DashScope qwen-max 从 chunks 合成 N 题 QA 评测集 |
| `eval_pipeline.py` | 跑评测: 调 /chat → 算 4 指标(Context Recall/Precision + Answer F1/EM) |
| `run.sh` | 一键执行: gen → eval 全流程 |

## 评测指标说明

| 指标 | 计算方式 | 阈值建议 |
|---|---|---|
| Context Recall | ground_truth_chunk 是否在 top-k 检索结果中 | 应 >0.7 |
| Context Precision | ground_truth_chunk 在 top-k 中的位次倒数 | 应 >0.4 |
| Answer F1 | answer vs ground_truth_answer 的中文 token F1 (jieba 分词) | 应 >0.3 |
| Answer EM | answer vs ground_truth 的 exact match (中文场景参考值) | 通常 <0.1 |

## 快速跑

```bash
cd ~/RagDoc/rag-doc-platform/eval

# 1. 装依赖
pip install openai pymysql python-dotenv requests jieba

# 2. 生成 30 题
python3 gen_questions.py 30

# 3. 跑评测
python3 eval_pipeline.py
```

执行后产出:
- `questions.jsonl` — 评测集
- `eval_results.jsonl` — 详细结果
- `eval_report.md` — Markdown 指标报告
