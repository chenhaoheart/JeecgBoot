-- 隐患识别台账入口改为 AI 概览（首页同一页面）
UPDATE `sys_permission`
SET `url` = '/dashboard/analysis',
    `component` = 'dashboard/Analysis',
    `description` = 'AI概览：已生成报告、知识库、流域与隐患要素'
WHERE `id` = '1990000000000000015';
