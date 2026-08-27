# Post-D3 Evaluation Residual Audit（E1/E2）— 2026-08-27

> 数据: pilot50_llmplanner(P0-2, Pre-D3) + pilot50_postd3(Post-D3), 冻结代码 62decb1。
> 本轮纯数据分析, 无生产代码修改。

## E1. Common-cohort paired validity — PASS

### COMMON_VALID_SET（固定 IDs/分母/failure policy）

```text
n = 46 (两次运行均为 planner_source=MODEL 的样本)
excluded(4): C_103(仅post失败), C_104/C_113(仅pre失败: PLAN_TOO_MANY_STEPS, 已由P1-A修复),
             S_160(两次均诚实冲突拒答)
```

### 共同 cohort 三组结果（n=46, paired bootstrap 95% CI）

| 对比 | ALL | C (n=22) | S (n=24) |
|---|---|---|---|
| PreD3 − Classic(同run配对) | **-0.083 [-0.167,-0.015]\*** | -0.100 [-0.223,-0.005]\* | -0.067 n.s. |
| PostD3 − Classic(同run配对) | **-0.002 [-0.089,+0.089] n.s.** | +0.023 n.s. | -0.025 n.s. |
| PostD3 − PreD3(Agentic跨run) | **+0.057 [+0.009,+0.117]\*** | +0.073 [+0.005,+0.150]\* | +0.042 边界 |
| Classic跨run(噪声桥对照) | -0.024 [-0.096,+0.033] n.s. | -0.050 n.s. | 0.000 |

**Headline 在固定 cohort 上成立**：-8.3pp（显著）→ -0.2pp（平手）；且因果方向（Agentic
自身改善 +5.7pp 显著）不被 Classic 跨run噪声（-2.4pp n.s.）解释。

### 0.936 vs 0.910 双数值解释（已解释，非 BLOCKED）

1. **分母差异（次要）**：0.9383 是 pre-run 的 47 样本分母，0.9104 是 post-run 的 48。
2. **两次独立 Classic 执行（主因）**：Classic 在两次运行中被重新执行。同 46 题逐题对比，
   8 题漂移 >5pp（极端如 C_101 1.00→0.10、S_170 1.00→0.10）——来源是 Classic 生成端
   非确定性（temp 0.3）+ judge 方差 + post-run 的 2 次 rerank 隧道瞬断（熔断降级 hybrid）。
   均值漂移 -2.4pp（n.s.），属于系统固有的 run-to-run 噪声，两个数值各自都是有效测量。
   **方法论结论**：跨run比较必须配 run 内 Classic 或用 Classic 跨run漂移作噪声桥——本文
   的 headline 使用 run 内配对差值，不受此影响。

```
E1_COMMON_COHORT_VALIDITY=PASS
```

## E2. Routing-signal actionability — ESCALATION_ONLY

### 信号可用时点（沿真实执行顺序）

```text
Request → [Classic路径: 检索→rerank→生成] — 无任何 sufficiency 评估
Request → [Agentic路径: Planner → Phase-0 执行 → ★semantic sufficiency → replan? → 生成]
```
semantic insufficiency 在 **Planner/Phase-0 之后**才产生 → 是 **post-execution control
signal**，不是 pre-routing signal。进入 Agentic 前免费获得该信号不存在。

### Triggered 子集的 post-selection bias 检验（关键）

同 10 题（common 内 replan>0）三条件对比：

```text
Classic 0.790 | PreD3-Agentic 0.710 (-23pp!) | PostD3-Agentic 0.820 (+3pp)
逐题: better=2 / worse=0 / same=8
未triggered对照(n=36)同跨run漂移: +4.2pp → 差中差(DiD)净效应 ≈ +6.8pp
```

**结论：选择偏差方向不成立**——该子集并非"Agentic 天然占优"的题目（Pre-D3 Agentic 在同
题上落后 23pp）；-23pp→+3pp 的翻转发生在固定同 IDs 上，归因于语义 replan。但 n=10，
逐题仅 2 题显著改善，**+3pp 本身不具个体显著性**——不能据此直接推出"可用它做 Router"。

### 方案对比（仅可行性）

| | A. Query-level Router | B. Retrieval-first Escalation |
|---|---|---|
| 信号 | 需 query 复杂度特征预测 Agentic 优势 | Classic 检索后跑语义 sufficiency(+1 LLM调用, ~1-2s, 规则extractor无需Planner) |
| 证据状态 | **无已验证的 pre-routing 特征**（slice/decomposition 与优势的关系在 D3 前后翻转, 不稳定） | 信号已验证存在(21%触发)且触发处恰是 Classic 弱点(0.79 vs 全局0.91) |
| 预期收益 | 无法估计 | 上限 ≈ 21%×3pp ≈ +0.6pp 系统级 — 在噪声内, **未证明值得成本** |
| 成本 | 分类器+校准集 | 全流量 +1 LLM 判定调用, 21% 走双倍路径(加权延迟 ≈ 8.6s vs 5.8s) |
| 判定 | 无证据基础, 不做 | 唯一证据对齐的设计, 但当前语料收益未证明 — 需专门实验 |

```
E2_ROUTING_SIGNAL=ESCALATION_ONLY
```

## 最终输出

```
E1_COMMON_COHORT_VALIDITY=PASS
E2_ROUTING_SIGNAL=ESCALATION_ONLY
POST_D3_FINAL_CLAIM=
  "固定 46 题共同 cohort 上: D1/D2/D3 修复使 Agentic 对 Classic 的配对差距从
   -8.3pp(95%CI显著) 收敛到 -0.2pp(统计平手); Agentic 自身改善 +5.7pp(显著),
   不被 Classic 跨run噪声(-2.4pp n.s.)解释; C 多跳 slice +2.3pp(首次名义反超)。
   语义 replan 仅在 21%(10/48)语义不足处触发, 该子集上 Pre-D3 落后 23pp →
   Post-D3 反超 3pp(n=10, 方向真实但个体不显著, DiD 净效应≈+7pp)。
   成本: ×2.8 延迟 / 3.4 LLM调用每run。生产默认仍 Classic;
   routing 唯一有证据基础的形态是 retrieval-first escalation, 但其系统级收益
   (+0.6pp上限)在当前语料未证明值得全流量+1次判定的成本。"
NEXT_TECHNICAL_ACTION=
  不实现 Router。若未来语料扩大后重启 Agentic 价值评估, 优先做
  'Classic + sufficiency 判定 → insufficient 才 escalate' 的 escalation 实验
  (需先在 Classic 语境下复测 sufficiency 触发率与判定质量), 而非 query-level 分类器。
```
