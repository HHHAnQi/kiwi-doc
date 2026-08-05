-- =========================================================
-- PR-1 / EMS-PR1: chat_traces 增加真实 Evidence 快照列
--
-- 设计要点:
--   1. evidence_snapshot 存 RetrieveService 三段 Evidence 的 JSON
--      (initialRetrieval / postRerank / finalContext), 让评测与 Trace 严格基于
--      Chat 实际 Context, 不再"再调一次 /retrieve"。
--   2. 默认 NULL — 老 trace 行与未启用 evidence 的请求完全兼容。
--   3. 不加 FK / 索引: 仅按 trace_id 主键查询, 读路径少 (~评测 + 调试)。
--   4. 与 docs/data/data-model.md "V12 PR-1 证据快照" 同步; ADR-0003 软引用不变。
-- =========================================================

ALTER TABLE chat_traces
    ADD COLUMN evidence_snapshot JSON NULL;
