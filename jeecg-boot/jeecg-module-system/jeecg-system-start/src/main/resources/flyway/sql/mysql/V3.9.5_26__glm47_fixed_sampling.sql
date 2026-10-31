-- 智谱 GLM-4.7 仅允许 temperature=1，其他值直接 400 拒绝：
-- {"code":"InvalidParameter","message":"`temperature` only 1 is allowed for this model"}
-- V3.9.5_6 升级时 model_params 写入的是 temperature=0.3，这里修正为 1（topP=0.95 本就符合要求）；
-- 仅修正仍为 0.3 的记录，已手工调整过的不覆盖。
UPDATE `airag_model`
SET `model_params` = REPLACE(`model_params`, '"temperature":0.3', '"temperature":1'),
    `update_time` = NOW()
WHERE `model_name` = 'glm-4.7'
  AND `model_params` LIKE '%"temperature":0.3%';
