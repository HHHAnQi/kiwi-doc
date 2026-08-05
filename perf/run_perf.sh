#!/usr/bin/env bash
# Task 10 RAG Performance Test — 一键 runner。
#
# 跑两组并发 (100 → 500), 各测 /chat (e2e) + /retrieve (retrieval only)。
# 拆分: Embedding / Retrieval / Rerank / LLM latency 通过两次 /retrieve 对比算出
#       (embed ≈ retrieve_rerank DISABLED latency; rerank ≈ rerank ENABLED - DISABLED;
#        LLM ≈ /chat - /retrieve; 详见 perf/performance_report.md §拆分公式)
#
# 产物: perf/out/{chat,retrieve}_{100,500}_stats.csv + rendering into report.
#
# 用法:
#   bash perf/run_perf.sh                 # 默认 host localhost:8080, 时长 60s/120s
#   PERF_HOST=http://10.0.0.5:8080 bash perf/run_perf.sh
#   PERF_DURATION_LOW=30 PERF_DURATION_HIGH=60 bash perf/run_perf.sh

set -euo pipefail

PERF_HOST="${PERF_HOST:-http://localhost:8080}"
PERF_TOKEN="${PERF_TOKEN:-${APP_DEV_TOKEN:-dev-token-change-me}}"
DURATION_LOW="${PERF_DURATION_LOW:-60}"       # 100 并发跑 60s
DURATION_HIGH="${PERF_DURATION_HIGH:-120}"    # 500 并发跑 120s (LLM API rate-limit 让出)

OUT_DIR="$(dirname "$0")/out"
mkdir -p "$OUT_DIR"

# ─── 健康检查 ─────────────────────────────────────────────
if ! curl -sf --max-time 3 "$PERF_HOST/actuator/health" >/dev/null 2>&1; then
  # 不强制 actuator, fallback 试 /api/v1/retrieve 看 4xx 至少表示活着
  code=$(curl -s --max-time 3 -o /dev/null -w '%{http_code}' "$PERF_HOST/api/v1/retrieve" \
         -H "Authorization: Bearer $PERF_TOKEN" \
         -H "Content-Type: application/json" \
         -d '{"query":"ping","top_k":1}' || echo 000)
  if [[ ! "$code" =~ ^2|4 ]]; then
    echo "✗ backend 不通 ($PERF_HOST /actuator/health 与 /api/v1/retrieve 都失败)"
    echo "  先启动: make run (或 docker-compose up -d)"
    exit 1
  fi
fi

command -v locust >/dev/null 2>&1 || {
  echo "✗ 没装 locust, 先: pip install locust"
  exit 1
}

echo "✓ backend 通, host=$PERF_HOST"

# ─── Phase 1: 100 并发 ─────────────────────────────────────
echo ""
echo "========== Phase 1: 100 并发 (chat) =========="
PERF_TARGET=chat PERF_TOKEN="$PERF_TOKEN" \
  locust -f perf/locustfile.py --headless \
    -u 100 -r 20 -t "${DURATION_LOW}s" \
    --host "$PERF_HOST" \
    --csv "$OUT_DIR/chat_100" \
    --only-summary || true

echo ""
echo "========== Phase 1: 100 并发 (retrieve) =========="
PERF_TARGET=retrieve PERF_TOKEN="$PERF_TOKEN" \
  locust -f perf/locustfile.py --headless \
    -u 100 -r 20 -t "${DURATION_LOW}s" \
    --host "$PERF_HOST" \
    --csv "$OUT_DIR/retrieve_100" \
    --only-summary || true

# ─── Phase 2: 500 并发 ─────────────────────────────────────
echo ""
echo "========== Phase 2: 500 并发 (chat) =========="
PERF_TARGET=chat PERF_TOKEN="$PERF_TOKEN" \
  locust -f perf/locustfile.py --headless \
    -u 500 -r 50 -t "${DURATION_HIGH}s" \
    --host "$PERF_HOST" \
    --csv "$OUT_DIR/chat_500" \
    --only-summary || true

echo ""
echo "========== Phase 2: 500 并发 (retrieve) =========="
PERF_TARGET=retrieve PERF_TOKEN="$PERF_TOKEN" \
  locust -f perf/locustfile.py --headless \
    -u 500 -r 50 -t "${DURATION_HIGH}s" \
    --host "$PERF_HOST" \
    --csv "$OUT_DIR/retrieve_500" \
    --only-summary || true

# ─── Phase 3 (可选): SSE TTFT (低并发, 测 TTFT 不测 QPS 上限) ─────────
if [[ "${PERF_SKIP_STREAM:-0}" != "1" ]]; then
  echo ""
  echo "========== Phase 3: SSE 50 并发 (TTFT) =========="
  PERF_TOKEN="$PERF_TOKEN" \
    locust -f perf/locustfile_stream.py --headless \
      -u 50 -r 10 -t 60s \
      --host "$PERF_HOST" \
      --csv "$OUT_DIR/stream_50" \
      --only-summary || true
fi

# ─── Render report ─────────────────────────────────────────
echo ""
echo "========== 渲染 report =========="
if [[ -f "$OUT_DIR/chat_100_stats.csv" ]]; then
  python3 perf/parse_csv.py "$OUT_DIR" > perf/performance_report.md
  echo "✓ perf/performance_report.md 已渲染"
else
  echo "⚠ 没生成 CSV (locust 启动失败?)"
fi
