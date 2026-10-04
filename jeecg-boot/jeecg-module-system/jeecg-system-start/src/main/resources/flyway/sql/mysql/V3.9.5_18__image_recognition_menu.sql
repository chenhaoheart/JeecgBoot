-- 图像识别子菜单（从应用门户抽取的独立功能页，作为与「山洪报告助手」并列的一级菜单）
INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `url`, `component`, `is_route`, `component_name`, `redirect`, `menu_type`, `perms`, `perms_type`, `sort_no`, `always_show`, `icon`, `is_leaf`, `keep_alive`, `hidden`, `hide_tab`, `description`, `create_by`, `create_time`, `del_flag`, `rule_flag`, `internal_or_external`)
SELECT '1990000000000000013',
       '',
       '图像识别', '/super/airag/imgrec/ImageRecognition', 'super/airag/imgrec/ImageRecognition',
       1, '', NULL, 0, NULL, '0', 2.85, 0, 'ant-design:scan-outlined', 1, 1, 0, 0,
       '上传图片进行AI图像识别', 'admin', NOW(), 0, 0, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '1990000000000000013');

-- 授权给 admin 角色（其他角色请按需在「系统管理-角色管理」中勾选授权）
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`)
SELECT '1990000000000000014', r.id, '1990000000000000013'
FROM sys_role r
WHERE r.role_code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM sys_role_permission WHERE role_id = r.id AND permission_id = '1990000000000000013') t);
