-- Upgrade an existing v2.3 database for knowledge-base level enable switches.
-- Safe to run more than once on the current database.

SET @kb_enabled_column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_knowledge_base'
      AND COLUMN_NAME = 'enabled'
);

SET @kb_enabled_ddl := IF(
    @kb_enabled_column_exists = 0,
    'ALTER TABLE `t_knowledge_base` ADD COLUMN `enabled` tinyint(1) NOT NULL DEFAULT ''1'' COMMENT ''whether enabled: 0 disabled, 1 enabled'' AFTER `collection_name`',
    'SELECT 1'
);

PREPARE kb_enabled_stmt FROM @kb_enabled_ddl;
EXECUTE kb_enabled_stmt;
DEALLOCATE PREPARE kb_enabled_stmt;

UPDATE `t_knowledge_base`
SET `enabled` = 1
WHERE `enabled` IS NULL;

-- Keep existing rows consistent with the hierarchical switch semantics:
-- a knowledge base is enabled only when at least one non-deleted document under it is enabled.
UPDATE `t_knowledge_base` kb
SET kb.`enabled` = CASE
    WHEN EXISTS (
        SELECT 1
        FROM `t_knowledge_document` doc
        WHERE doc.`kb_id` = kb.`id`
          AND doc.`deleted` = 0
          AND doc.`enabled` = 1
    ) THEN 1
    ELSE 0
END
WHERE kb.`deleted` = 0;
