-- 山洪一张图数据维护：独立菜单（流域/防治对象/隐患要素等表格数据的导入与编辑，按调查年份区分）
INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `url`, `component`, `is_route`, `component_name`, `redirect`, `menu_type`, `perms`, `perms_type`, `sort_no`, `always_show`, `icon`, `is_leaf`, `keep_alive`, `hidden`, `hide_tab`, `description`, `create_by`, `create_time`, `del_flag`, `rule_flag`, `internal_or_external`)
SELECT '1990000000000000011',
       '',
       '一张图数据维护', '/super/airag/shreport/ShMapDataAdmin', 'super/airag/shreport/ShMapDataAdmin',
       1, 'ShMapDataAdmin', NULL, 0, NULL, '0', 1.16, 0, 'ant-design:database-outlined', 1, 1, 0, 0,
       '流域、防治对象、隐患要素等一张图数据的导入与维护，按调查年份区分', 'admin', NOW(), 0, 0, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '1990000000000000011');

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`)
SELECT '1990000000000000012', r.id, '1990000000000000011'
FROM sys_role r
WHERE r.role_code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM sys_role_permission WHERE role_id = r.id AND permission_id = '1990000000000000011') t);
