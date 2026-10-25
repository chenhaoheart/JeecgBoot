-- 后端已移除积木报表(JimuReport)/积木BI/Chat2BI 模块（前端已先行下线），清理菜单表中残留的积木相关菜单与按钮权限
-- 按 URL/组件/权限标识模式匹配，幂等可重复执行（当前库仅剩 jmreport:map:* 两条孤儿按钮权限，其余环境可能有积木报表/仪表盘/Chat2BI 菜单）

-- 1. 先删除角色-权限关联
DELETE FROM `sys_role_permission`
WHERE `permission_id` IN (
    SELECT `id` FROM (
        SELECT `id` FROM `sys_permission`
        WHERE `url` LIKE '%jmreport%'
           OR `url` LIKE '%jimubi%'
           OR `url` LIKE '/drag/%'
           OR `url` LIKE '%chat2bi%'
           OR `component` LIKE '%jmreport%'
           OR `component` LIKE '%jimureport%'
           OR `perms` LIKE 'jmreport:%'
           OR `perms` LIKE 'chat2bi:%'
    ) t
);

-- 2. 删除菜单/按钮权限
DELETE FROM `sys_permission`
WHERE `url` LIKE '%jmreport%'
   OR `url` LIKE '%jimubi%'
   OR `url` LIKE '/drag/%'
   OR `url` LIKE '%chat2bi%'
   OR `component` LIKE '%jmreport%'
   OR `component` LIKE '%jimureport%'
   OR `perms` LIKE 'jmreport:%'
   OR `perms` LIKE 'chat2bi:%';
