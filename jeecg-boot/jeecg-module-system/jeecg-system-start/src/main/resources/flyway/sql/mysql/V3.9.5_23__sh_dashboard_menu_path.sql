-- 隐患识别台账单独走 /dashboard/ai，避免和首页抢同一个路由标题
UPDATE `sys_permission`
SET `url` = '/dashboard/ai',
    `component` = 'dashboard/aiBoard/index',
    `description` = 'AI概览：已生成报告、知识库、流域与隐患要素'
WHERE `id` = '1990000000000000015';
