# eval/golden Schema

> Phase 0 资产化的题库, 是后续 Phase 1/2/3 所有评测的"源真相"。

## 文件清单

| 文件 | 内容 | 生成方式 |
|---|---|---|
| `golden.jsonl` | 100 题 canonical(从 `eval/questions.real.long_gt_100.jsonl` 复制 + `source_dataset` 字段) | 一次性脚本 |
| `golden.with_labels.jsonl` | golden.jsonl + `question_type` 标签(启发式自动打) | `python3 eval/label_questions.py` |

## 字段(field)

| field                    | type   | 必填 | 说明 |
|--------------------------|--------|------|------|
| `question`               | string | ✅   | 用户查询(简短自然语言) |
| `ground_truth_answer`    | string | ✅   | 短版参考答案(LLM-extractive, 含同源污染, Phase 0 任务即校准此问题) |
| `ground_truth_chunk_id`  | int    | ✅   | 答案出自语料库的 chunk 主键(对应 `chunks.id`) |
| `ground_truth_doc_id`    | int    | ✅   | 答案出自语料库的 document 主键 |
| `answer`                 | string | ⚠️   | 长 GT(原始生成题时的长答案), 仅参考 |
| `answer_short`           | string | ⚠️   | 短版答案(供 RAGAS context_recall 用) |
| `topic`                  | string | ⚠️   | 题目主题 grouping(auto-gen-XX 编号, 非真业务分类) |
| `source_dataset`         | string | ✅   | 标记题目来源, 便于跨 golden 版本追踪 |
| `question_type`          | string | ⚠️   | 仅在 `with_labels.jsonl`,见下表 |

## `question_type` 取值

启发式规则, 优先级 troubleshoot > config/multi_hop > procedural > factual > other。

| label        | 含义                  | 关键词样例                          |
|--------------|-----------------------|-------------------------------------|
| `factual`    | 事实/概念定义         | "是什么/定义/意思"                  |
| `config`     | 配置项/文件           | "配置/.yml/字段/properties"         |
| `multi_hop`  | 跨文档/对比           | "区别/对比/相比/与...哪个"          |
| `troubleshoot` | 故障排查           | "报错/不生效/失败/怎么办"           |
| `procedural` | 步骤/操作            | "如何/怎么/步骤/流程"               |
| `other`      | 未命中规则(留待人工) | —                                   |

## 当前 100 题分布

```
procedural     42  ( 42.0%)
config         34  ( 34.0%)
factual         8  (  8.0%)
troubleshoot    6  (  6.0%)
multi_hop       5  (  5.0%)
other           5  (  5.0%)
```

## 重新生成

题库后续可被人工修订, 修订后重跑:

```bash
python3 eval/label_questions.py
# 或对其它 jsonl:
python3 eval/label_questions.py --in <path> --out <path>
```

## 已知限制(诚实标注)

1. **ground_truth_answer 仍是 LLM 生成**(extractive GT), 即 "LLM 自评" 的同源污染源
   - Phase 0 并**不修 GT**(无法纯靠脚本修复)
   - Phase 0 解决 judge 一侧(用异族 judge)
   - 真实污染彻底消除要等 Phase 2 上线后收集 human-collected query
2. **`topic=auto-gen-XX`** 不是真业务分类, 不能用于业务推理
3. **启发式标签存疑**: `other` 5 题与 `factual/troubleshoot` 边界处的题目可能误标
   - Phase 2 时人工抽样校正(>=30 题)
