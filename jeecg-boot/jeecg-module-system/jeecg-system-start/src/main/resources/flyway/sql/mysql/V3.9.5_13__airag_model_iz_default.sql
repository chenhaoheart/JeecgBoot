-- 默认模型改为前端可配置：airag_model 增加 iz_default 标记（同一 model_type 下最多一个）。
-- 未设置默认模型时，后端仍回退 jeecg.ai-chat 平台底层配置（yml）。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 守卫实现幂等。
SET @col := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'airag_model' AND column_name = 'iz_default');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `airag_model` ADD COLUMN `iz_default` int DEFAULT 0 COMMENT ''是否默认模型(1=默认;同一model_type互斥;前端AI模型页设置)''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 种子默认对话模型（幂等：已有默认 LLM 时跳过）。
-- 当前智谱 glm-4.7 账户欠费、火山方舟对话模型可用，故种子 ark-code-latest；之后可在前端「AI模型」页改设。
UPDATE `airag_model` m
SET m.`iz_default` = 1, m.`update_time` = NOW()
WHERE m.`model_type` = 'LLM' AND m.`model_name` = 'ark-code-latest'
  AND NOT EXISTS (
      SELECT 1 FROM (
          SELECT t.`id` FROM `airag_model` t WHERE t.`model_type` = 'LLM' AND t.`iz_default` = 1
      ) x
  );
