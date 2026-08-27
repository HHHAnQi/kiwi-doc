-- P2-D5(A): 独立的 process decision summary —— 与 terminal_reason_code(生命周期原因,
-- 会被 Pipeline 最终 ANSWERED 覆盖为 PLANNED_ANSWER_READY)分离, 记录"为什么得到
-- 最终结果"且一经写入不再覆盖:
--   INITIAL_SUFFICIENT / REPLAN_SUFFICIENT / REPLAN_EXHAUSTED_FALLBACK /
--   REFUSED_CONFLICT / TOOL_FAILURE
ALTER TABLE agent_run ADD COLUMN decision_summary VARCHAR(64) NULL;
