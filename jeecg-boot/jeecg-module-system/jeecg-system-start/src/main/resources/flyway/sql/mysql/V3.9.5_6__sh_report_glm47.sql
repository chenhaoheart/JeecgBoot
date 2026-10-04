-- 山洪报告章节流程改绑到已存在的智谱 GLM-4.7（airag_model.id=1897883052995006466）

UPDATE `airag_model`
SET `name` = '智谱 GLM-4.7',
    `provider` = 'OPENAI',
    `model_name` = 'glm-4.7',
    `credential` = '{"apiKey":"请填写智谱APIKey"}',
    `base_url` = 'https://open.bigmodel.cn/api/paas/v4',
    `model_params` = '{"temperature":0.3,"topP":0.95,"maxTokens":4096,"timeout":120}',
    `activate_flag` = 1,
    `update_time` = NOW()
WHERE `id` = '1897883052995006466';

UPDATE `airag_model` SET `activate_flag` = 0, `update_time` = NOW()
WHERE `id` = '1897481367743143938';

UPDATE `airag_flow`
SET `design` = REPLACE(REPLACE(`design`, '1897481367743143938', '1897883052995006466'), '2009470000000000001', '1897883052995006466'),
    `update_time` = NOW()
WHERE `name` LIKE '山洪报告_%'
  AND (`design` LIKE '%1897481367743143938%' OR `design` LIKE '%2009470000000000001%');
