-- 隐患识别台账已并入「一张图数据维护」左侧「AI隐患分析」，侧栏不再单独显示
UPDATE `sys_permission`
SET `hidden` = 1,
    `del_flag` = 1,
    `update_by` = 'admin',
    `update_time` = NOW()
WHERE `id` = '1990000000000000015'
   OR `name` = '隐患识别台账';
