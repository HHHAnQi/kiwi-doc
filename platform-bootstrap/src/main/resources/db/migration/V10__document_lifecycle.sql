-- V10: Document Index Lifecycle State Machine (Task 4)
-- 1) 历史 READY 数据迁移到 INDEXED (新状态机 INDEXED 才是检索终态; READY 已废弃)
--    只动未软删数据: 软删数据 status 在 sweeper 路径里无关紧要, 不动避免误改历史
UPDATE documents SET status = 'INDEXED'
  WHERE status = 'READY' AND deleted_at IS NULL;

-- 2) 加 last_state_change_at: reconcile job 扫"卡在 in-flight 状态超时"用
--    默认 CURRENT_TIMESTAMP 让历史数据立刻可被 reconcile (但不会误判 — 历史 PARSING 通常早就 FAILED,
--    真正卡住的少数行会被 reconcile 修复一次, 可接受)
ALTER TABLE documents
  ADD COLUMN last_state_change_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    COMMENT 'V10: 状态机最后变更时间, reconcile job 扫卡死用',
  ADD INDEX idx_documents_state_change (status, last_state_change_at);
