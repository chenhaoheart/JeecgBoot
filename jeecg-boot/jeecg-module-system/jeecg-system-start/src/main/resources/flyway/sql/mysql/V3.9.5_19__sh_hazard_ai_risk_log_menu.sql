-- 隐患识别台账：一张图上每次 AI 风险识别的留痕查询页
INSERT INTO `sys_permission` (`id`, `parent_id`, `name`, `url`, `component`, `is_route`, `component_name`, `redirect`, `menu_type`, `perms`, `perms_type`, `sort_no`, `always_show`, `icon`, `is_leaf`, `keep_alive`, `hidden`, `hide_tab`, `description`, `create_by`, `create_time`, `del_flag`, `rule_flag`, `internal_or_external`)
SELECT '1990000000000000015',
       '',
       '隐患识别台账', '/super/airag/shreport/ShHazardAiRiskLog', 'super/airag/shreport/ShHazardAiRiskLog',
       1, '', NULL, 0, NULL, '0', 1.17, 0, 'ant-design:file-search-outlined', 1, 1, 0, 0,
       '一张图隐患点 AI 风险识别的操作留痕', 'admin', NOW(), 0, 0, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id = '1990000000000000015');

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`)
SELECT '1990000000000000016', r.id, '1990000000000000015'
FROM sys_role r
WHERE r.role_code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM sys_role_permission WHERE role_id = r.id AND permission_id = '1990000000000000015') t);
