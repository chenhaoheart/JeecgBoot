-- 山洪一张图：独立一级菜单（不挂在报告助手或 AI 目录下）
INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `url`, `component`, `is_route`, `component_name`, `redirect`, `menu_type`, `perms`, `perms_type`, `sort_no`, `always_show`, `icon`, `is_leaf`, `keep_alive`, `hidden`, `hide_tab`, `description`, `create_by`, `create_time`, `del_flag`, `rule_flag`, `internal_or_external`)
SELECT '1990000000000000009',
       '',
       '山洪一张图', '/super/airag/shreport/ShReportOneMap', 'super/airag/shreport/onemap/ShReportOneMap',
       1, '', NULL, 0, NULL, '0', 1.15, 0, 'ant-design:environment-outlined', 1, 1, 0, 0,
       '按流域/政区查看风险隐患与防治对象，点选流域生成报告', 'admin', NOW(), 0, 0, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '1990000000000000009');

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`)
SELECT '1990000000000000010', r.id, '1990000000000000009'
FROM sys_role r
WHERE r.role_code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM sys_role_permission WHERE role_id = r.id AND permission_id = '1990000000000000009') t);
