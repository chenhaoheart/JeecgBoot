-- 山洪报告助手菜单（挂在「AI应用平台」目录下；若目录名不同，请将子查询中的名称替换为实际目录名）
INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `url`, `component`, `is_route`, `component_name`, `redirect`, `menu_type`, `perms`, `perms_type`, `sort_no`, `always_show`, `icon`, `is_leaf`, `keep_alive`, `hidden`, `hide_tab`, `description`, `create_by`, `create_time`, `del_flag`, `rule_flag`, `internal_or_external`)
SELECT '1990000000000000001',
       (SELECT id FROM (SELECT id FROM sys_permission WHERE name = 'AI应用平台' AND menu_type = 0 LIMIT 1) t),
       '山洪报告助手', '/super/airag/shreport/ShReportAssistant', 'super/airag/shreport/ShReportAssistant',
       1, '', NULL, 1, NULL, '0', 9.00, 0, 'ant-design:file-text-outlined', 1, 1, 0, 0,
       '对话式生成山洪灾害风险隐患调查报告', 'admin', NOW(), 0, 0, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '1990000000000000001');

-- 授权给 admin 角色（其他角色请按需在「系统管理-角色管理」中勾选授权）
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`)
SELECT '1990000000000000002', r.id, '1990000000000000001'
FROM sys_role r
WHERE r.role_code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM sys_role_permission WHERE role_id = r.id AND permission_id = '1990000000000000001') t);
