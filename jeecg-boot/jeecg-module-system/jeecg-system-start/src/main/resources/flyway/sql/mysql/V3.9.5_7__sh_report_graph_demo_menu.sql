-- 山洪报告编排图 Demo 菜单（挂在「AI应用平台」下，与山洪报告助手并列）
INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `url`, `component`, `is_route`, `component_name`, `redirect`, `menu_type`, `perms`, `perms_type`, `sort_no`, `always_show`, `icon`, `is_leaf`, `keep_alive`, `hidden`, `hide_tab`, `description`, `create_by`, `create_time`, `del_flag`, `rule_flag`, `internal_or_external`)
SELECT '1990000000000000007',
       (SELECT id FROM (SELECT id FROM sys_permission WHERE name = 'AI应用平台' AND menu_type = 0 LIMIT 1) t),
       '报告编排图', '/super/airag/shreport/ShReportGraphDemo', 'super/airag/shreport/ShReportGraphDemo',
       1, '', NULL, 1, NULL, '0', 9.10, 0, 'ant-design:apartment-outlined', 1, 1, 0, 0,
       '山洪报告 LangGraph 节点与运行状态 Demo', 'admin', NOW(), 0, 0, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '1990000000000000007');

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`)
SELECT '1990000000000000008', r.id, '1990000000000000007'
FROM sys_role r
WHERE r.role_code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM sys_role_permission WHERE role_id = r.id AND permission_id = '1990000000000000007') t);
