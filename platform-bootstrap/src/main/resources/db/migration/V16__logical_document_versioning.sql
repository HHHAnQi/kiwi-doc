-- V16: 把“每个 source 一个默认版本”收敛为“每个逻辑文件一个当前版本”。
-- source 继续表示产品/知识域；logical_document_key 才表示跨版本稳定的文件身份。

ALTER TABLE documents
    ADD COLUMN logical_document_key VARCHAR(128) NULL AFTER version;

-- 历史数据无法可靠反推外部文件 ID，保守按规范化文件名分组。
-- 新上传由应用去扩展名并剥离版本段；连接器可显式传稳定 source key。
UPDATE documents
SET logical_document_key = LEFT(
        LOWER(
            CASE
                WHEN LOCATE('.', original_filename) > 1
                    THEN LEFT(original_filename, LENGTH(original_filename) - LENGTH(SUBSTRING_INDEX(original_filename, '.', -1)) - 1)
                ELSE original_filename
            END),
        128)
WHERE logical_document_key IS NULL;

-- 尽力剥离历史文件名中的独立语义版本段，使 guide-1.0.pdf / guide-2.0.pdf 归为同一 key。
UPDATE documents
SET logical_document_key = COALESCE(
        NULLIF(
            TRIM(BOTH '-' FROM REGEXP_REPLACE(
                logical_document_key,
                '(^|[-_[:space:]])v?[0-9]+[.][0-9]+([.][0-9]+){0,2}([-_.]?(rc|ga|m|alpha|beta)[0-9]?)?([-_[:space:]]|$)',
                '-')),
            ''),
        LEFT(LOWER(original_filename), 128));

ALTER TABLE documents
    MODIFY COLUMN logical_document_key VARCHAR(128) NOT NULL DEFAULT 'unknown';

-- 历史上若同租户存在相同文件名、相同业务版本但内容不同，不能让上线迁移被唯一索引阻断。
-- 保守把第二条起视为独立 legacy 逻辑文档；后续管理员可人工合并。
UPDATE documents d
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, logical_document_key, COALESCE(version, '')
               ORDER BY created_at ASC, id ASC
           ) AS rn
    FROM documents
) conflicts ON conflicts.id = d.id
SET d.logical_document_key = LEFT(
        CONCAT(d.logical_document_key, '-legacy-', d.id), 128)
WHERE conflicts.rn > 1;

-- V7 的 source 级 current 过粗；对每个逻辑文件选择最新一条 INDEXED 版本。
UPDATE documents SET is_default = FALSE;

UPDATE documents d
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, logical_document_key
               ORDER BY created_at DESC, id DESC
           ) AS rn
    FROM documents
    WHERE status = 'INDEXED' AND deleted_at IS NULL
) ranked ON ranked.id = d.id
SET d.is_default = (ranked.rn = 1);

-- MySQL 无 partial unique：generated column 只在 current=true 且未删除时产生值；
-- 历史/非 current 行为 NULL，利用 UNIQUE 允许多个 NULL 的语义。
ALTER TABLE documents
    ADD COLUMN current_logical_document_key VARCHAR(128)
        GENERATED ALWAYS AS (
            CASE
                WHEN is_default = TRUE AND deleted_at IS NULL THEN logical_document_key
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_tenant_current_logical_document
        (tenant_id, current_logical_document_key),
    ADD UNIQUE KEY uk_tenant_logical_version
        (tenant_id, logical_document_key, version),
    ADD INDEX idx_tenant_logical_document
        (tenant_id, logical_document_key, created_at);
