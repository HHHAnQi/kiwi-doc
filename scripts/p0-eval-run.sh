#!/usr/bin/env bash
# ============================================================
# P0 微评估一键脚本(V3-W2 真值评估)
#
# 目的(spec §4 + 验收报告 §4):
#   1. 在已起 chat-app + corpus 的状态下, 重 curate 30 题 ground truth
#   2. rerank ON 跑 RAGAS ≥3 次 取 mean ± std
#   3. 输出新 baseline 表(docs/v3/v3-acceptance-report.md §4 真值)
#
# 必跑前置(脚本不会替你执行):
#   1. make up(中间件全栈, 含 RocketMQ/BGE-M3)
#   2. Autodl reranker 已起 + SSH 隧道把 8084 转到本地
#       ssh -L 8084:localhost:8081 -N root@autodl-xxx.com
#   3. chat-app 已在 8080 跑, profile=dev, RAG_PARSER_MODE=sync, RAG_RERANK_ENABLED=true
#   4. corpus 已扩到 150+ docs(可前置跑 bulk_upload_corpus.py)
#
# 用法:
#   ./scripts/p0-eval-run.sh                 # 默认 3 次
#   RUNS=5 ./scripts/p0-eval-run.sh          # 自定 5 次
#   SKIP_CURATE=1 ./scripts/p0-eval-run.sh   # 跳过重 curate(已有新 ground truth)
#   SKIP_CORPUS=1 ./scripts/p0-eval-run.sh   # 跳过扩 corpus(已扩到目标)
#
# 输出:
#   eval/eval_p0_run{i}.md       每次 RAGAS 报告
#   eval/p0_summary.md           综合报告(mean ± std, 填进 acceptance report §4)
# ============================================================

set -uo pipefail

# ---- 参数 ----
RUNS="${RUNS:-3}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

CHAT_URL="${CHAT_URL:-http://localhost:8080}"
# ragas_pipeline.py 期望 CHAT_URL 是完整 chat 接口 URL(含 /api/v1/chat);
# bulk_upload_corpus.py 期望 UPLOAD_URL 是 root URL(含 /api/v1/documents).
# 用户只传 root URL 时统一拼好下游用, 调 P0 时不用分别设.
ROOT_APP_URL="${CHAT_URL%/}"  # 去尾斜杠
CHAT_URL="${ROOT_APP_URL}/api/v1/chat"
# bulk_upload_corpus.py 默认 UPLOAD_URL 8092, 这里跟 root 对齐(同进程同端口)
UPLOAD_URL="${UPLOAD_URL:-${ROOT_APP_URL}/api/v1/documents}"
CHAT_TOKEN="${TEST_AUTH_TOKEN:-dev-token-change-me}"
export CHAT_URL UPLOAD_URL
MYSQL_PORT="${MYSQL_PORT:-3307}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_ROOT_PASSWORD:-rootpass}"
MYSQL_DB="${MYSQL_DB:-ragdoc}"
TARGET_CORPUS="${TARGET_CORPUS:-150}"

EVAL_DIR="$PROJECT_ROOT/eval"
SUMMARY_FILE="$EVAL_DIR/p0_summary.md"

log() {
  local ts
  ts=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  echo "[$ts] $*"
}

fail() {
  log "FAIL: $*"
  exit 1
}

# ============================================================
# Step 0: 前置检查
# ============================================================
log "step0 前置检查"
# 健康检查用 root app URL 不是 chat endpoint
curl -sf "${ROOT_APP_URL}/actuator/health" > /dev/null || fail "chat-app 不可达: ${ROOT_APP_URL}"
log "  ✓ chat-app 健康 (${ROOT_APP_URL}/actuator/health)"

curl -sf http://localhost:8082/health > /dev/null 2>&1 || log "  ⚠ BGE-M3 8082 不可达(脚本仍跑, ragas_pipeline 可能挂)"
curl -sf http://localhost:8084/health > /dev/null 2>&1 || log "  ⚠ Reranker 8084 不可达(预期 +5-7pp faith 拿不到)"

[ -n "${LLM_API_KEY:-}" ] || log "  ⚠ LLM_API_KEY 空, gen_questions / ragas_pipeline 会挂"
[ -f "$PROJECT_ROOT/.env" ] && log "  ✓ .env 存在(LLM_API_KEY 等基础配置 OK)"

# ============================================================
# Step 1: 校验当前 corpus 状态
# ============================================================
log "step1 校验 corpus 现状(目标 ≥ ${TARGET_CORPUS} docs)"
CUR_DOC_COUNT=$(mysql -h127.0.0.1 -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" -N -e \
  "SELECT COUNT(*) FROM documents WHERE deleted_at IS NULL" 2>/dev/null) || fail "MySQL 查询失败"
CUR_CHUNK_COUNT=$(mysql -h127.0.0.1 -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" -N -e \
  "SELECT COUNT(*) FROM chunks" 2>/dev/null) || fail "MySQL 查询失败(chunks)"

log "  documents ready/total: ${CUR_DOC_COUNT} / chunks: ${CUR_CHUNK_COUNT}"

if [ "${SKIP_CORPUS:-0}" != "1" ] && [ "$CUR_DOC_COUNT" -lt "$TARGET_CORPUS" ]; then
  log "  corpus < ${TARGET_CORPUS}, 启动 bulk_upload 扩到目标"
  MAX_PER_SOURCE=$(( TARGET_CORPUS / 5 + 2 ))
  log "  bulk_upload: MAX_PER_SOURCE=${MAX_PER_SOURCE}(总 ≈ $((MAX_PER_SOURCE * 5)))"
  MAX_PER_SOURCE="$MAX_PER_SOURCE" MAX_WORKERS=1 \
    python3 scripts/bulk_upload_corpus.py || fail "corpus 上传失败"
fi

# ============================================================
# Step 2: 重新 curate 30 题 ground truth(基于新 corpus)
# ============================================================
if [ "${SKIP_CURATE:-0}" != "1" ]; then
  log "step2 重 curate 30 题 ground truth(基于当前 ${CUR_DOC_COUNT} docs corpus)"
  python3 eval/gen_questions.py 30 || fail "gen_questions 失败"
  # 备份旧 curated(对比研究用)
  if [ -f eval/questions.curated.jsonl ]; then
    mv eval/questions.curated.jsonl "eval/questions.curated.legacy.$(date +%s).jsonl"
    log "  旧 questions.curated.jsonl 已备份"
  fi
  mv eval/questions.jsonl eval/questions.curated.jsonl
  log "  ✓ 新 ground truth → eval/questions.curated.jsonl"
else
  log "step2 SKIP_CURATE=1, 沿用现有 questions.curated.jsonl"
fi

# ============================================================
# Step 3: rerank ON 跑 RAGAS RUNS 次
# ============================================================
log "step3 rerank ON 跑 RAGAS ${RUNS} 次"

# 备份历史 RAGAS 产物避免污染
TS="$(date +%s)"
mkdir -p "$EVAL_DIR/p0_runs"
cp eval/eval_ragas_report.md "$EVAL_DIR/p0_runs/eval_p0_run_before_${TS}.md" 2>/dev/null || true

run_results=()
for i in $(seq 1 "$RUNS"); do
  log "  --- RAGAS run ${i}/${RUNS} ---"
  OUT="$EVAL_DIR/p0_runs/eval_p0_run${i}.md"
  python3 eval/ragas_pipeline.py > "$EVAL_DIR/p0_runs/run${i}.stdout" 2>&1
  RC=$?
  if [ $RC -ne 0 ]; then
    log "  run ${i} 挂了(rc=${RC}), 看 $EVAL_DIR/p0_runs/run${i}.stdout"
    fail "RAGAS run ${i} failed"
  fi
  cp eval/eval_ragas_report.md "$OUT"
  log "  ✓ run ${i} → $OUT"
done

# ============================================================
# Step 4: 算 mean ± std
# ============================================================
log "step4 算 mean ± std + 写 $SUMMARY_FILE"
python3 << PYEOF
import re, statistics
from pathlib import Path

EVAL_DIR = Path("${EVAL_DIR}")
runs = []
for i in range(1, ${RUNS} + 1):
    p = EVAL_DIR / "p0_runs" / f"eval_p0_run{i}.md"
    if not p.exists():
        continue
    text = p.read_text(encoding="utf-8")
    # 寻找 4 个指标
    metrics = {}
    for m in ["faithfulness", "answer_relevancy", "context_precision", "context_recall"]:
        match = re.search(rf"{m}\s*:?\s*([0-9]+\.[0-9]+)", text)
        if match:
            metrics[m] = float(match.group(1))
    runs.append(metrics)

metrics_order = ["faithfulness", "answer_relevancy", "context_precision", "context_recall"]
lines = ["# P0 微评估汇总报告(生成于 $(date -u +'%Y-%m-%dT%H:%M:%SZ'))", ""]
lines.append(f"基于 ${RUNS} 次 RAGAS 跑(run1-${RUNS}) 计算的 mean ± std")
lines.append("")
lines.append("| metric | run1 | run2 | run3 | mean | std |")
lines.append("|---|---|---|---|---|---|")
summary = {}
for m in metrics_order:
    vals = [r.get(m) for r in runs if m in r]
    if len(vals) < 2:
        mean_val = vals[0] if vals else None
        lines.append(f"| {m} | " + " | ".join(f"{v:.4f}" for v in vals) + " | " + " | ".join(["N/A"] * (${RUNS} - len(vals))) + f" | {mean_val} | N/A |")
        summary[m] = {"mean": mean_val, "std": None}
        continue
    mean_val = statistics.mean(vals)
    std_val = statistics.stdev(vals) if len(vals) >= 2 else 0.0
    cells = " | ".join(f"{v:.4f}" for v in vals)
    lines.append(f"| {m} | {cells} | {mean_val:.4f} | {std_val:.4f} |")
    summary[m] = {"mean": mean_val, "std": std_val}

lines.append("")
lines.append("## baseline 升级")
lines.append("把这 mean 数字拷到 eval/baseline_v3_judge_plus.md 作为新 baseline:")
for m in metrics_order:
    s = summary.get(m, {})
    if s.get("mean"):
        lines.append(f"- {m}: {s['mean']:.4f} (std={s.get('std', 0)})")
lines.append("")
lines.append("## 填回验收报告")
lines.append("docs/v3/v3-acceptance-report.md §4.1 表格填写:")
for m in metrics_order:
    s = summary.get(m, {})
    if s.get("mean"):
        lines.append(f"| {m} | {s['mean']:.4f} | {s.get('std', 0):.4f} | (填备注, 含 judge LLM = glm-4-plus + thinking disabled) |")

Path("${SUMMARY_FILE}").write_text("\n".join(lines), encoding="utf-8")
print("\n".join(lines))
PYEOF

log "=============================================="
log "P0 微评估完成: ${RUNS} 次 RAGAS 跑完 mean ± std"
log "  → ${SUMMARY_FILE}"
log "  下一步:"
log "   1. cp eval/p0_runs/eval_p0_run1.md eval/baseline_v3_judge_plus.md"
log "   2. 填 docs/v3/v3-acceptance-report.md §4 数字"
log "   3. commit 'docs(eval): P0 微评估真数字 + baseline 升级'"
log "=============================================="

# ============================================================
# Step 5: 提示 kill -9 演练(独立跑, 5-10 min)
# ============================================================
log "BONUS: 可选立刻跑 kill -9 演练(填验收报告 §2 DoD-1/4 实跑 PASS)"
log "  ./scripts/v3-kill-9-drill.sh"
log "  或 DRILL_FAST_LEASE=1 ./scripts/v3-kill-9-drill.sh  (快进模式跳过 5min 等待)"
