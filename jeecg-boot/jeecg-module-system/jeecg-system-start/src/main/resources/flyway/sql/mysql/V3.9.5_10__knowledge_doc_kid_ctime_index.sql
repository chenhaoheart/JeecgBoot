-- 知识库文档列表按 (knowledge_id, create_time) 排序走索引：
-- 导入整批流域报告后文档数上千且 content 为整章大文本，
-- 无索引时 filesort 会撑爆 MySQL sort buffer（error 1038 Out of sort memory），页面打开超时。
-- 幂等写法：索引已存在（如手工加过）则跳过。
SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'airag_knowledge_doc'
      AND index_name = 'idx_kid_ctime'
);
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE `airag_knowledge_doc` ADD INDEX `idx_kid_ctime` (`knowledge_id`, `create_time`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
