-- 山洪报告章节流程的 LLM 节点超时原为 120 秒。
-- 外业调查等章节要把整表隐患对象写成公文，火山方舟经常超过 120 秒，
-- 客户端超时会被翻译成「当前用户较多，排队中，请稍后再试！」。
-- 这里把仍为 120 秒的节点抬到 600 秒；已手工改得更长的不覆盖。
UPDATE `airag_flow`
SET `design` = REPLACE(`design`, '"timeout": 120', '"timeout": 600'),
    `update_time` = NOW()
WHERE `name` LIKE '山洪报告_%'
  AND `design` LIKE '%"timeout": 120%';

UPDATE `airag_flow`
SET `design` = REPLACE(`design`, '"timeout":120', '"timeout":600'),
    `update_time` = NOW()
WHERE `name` LIKE '山洪报告_%'
  AND `design` LIKE '%"timeout":120%';
