-- V23: agent 系 CHAR(64) → VARCHAR(64) (plan_hash / input_hash / idempotency_key)
--
-- V13 建表用了 CHAR(64), 但 AgentRunEntity 的映射是默认 String → VARCHAR(64);
-- Hibernate schema-validation 在全新库上直接 fail-fast:
--   Schema-validation: wrong column type encountered in column [plan_hash]
--   in table [agent_run]; found [char (Types#CHAR)], expecting [varchar(64)]
-- (存量开发库此前靠历史数据/宽松校验侥幸通过, 全新部署必挂。)
--
-- CHAR(64) 定长还会把不足 64 位的 hash 右侧补空格, 读出即脏数据 — VARCHAR 是正确类型。

ALTER TABLE agent_run MODIFY COLUMN plan_hash VARCHAR(64) NOT NULL;
ALTER TABLE agent_step MODIFY COLUMN input_hash VARCHAR(64) NOT NULL;
ALTER TABLE agent_step MODIFY COLUMN idempotency_key VARCHAR(64) NULL;
