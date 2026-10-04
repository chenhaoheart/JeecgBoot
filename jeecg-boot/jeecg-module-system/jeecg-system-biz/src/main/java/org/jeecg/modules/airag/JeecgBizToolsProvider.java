package org.jeecg.modules.airag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.util.PasswordUtil;
import org.jeecg.common.util.SpringContextUtils;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.airag.llm.consts.LLMConsts;
import org.jeecg.modules.airag.llm.entity.AiragKnowledge;
import org.jeecg.modules.airag.llm.entity.AiragKnowledgeDoc;
import org.jeecg.modules.airag.llm.handler.EmbeddingHandler;
import org.jeecg.modules.airag.llm.handler.JeecgToolsProvider;
import org.jeecg.modules.airag.llm.service.IAiragKnowledgeDocService;
import org.jeecg.modules.airag.llm.service.IAiragKnowledgeService;
import org.jeecg.modules.base.service.BaseCommonService;
import org.jeecg.modules.system.entity.SysRole;
import org.jeecg.modules.system.entity.SysUser;
import org.jeecg.modules.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * for [QQYUN-13565]【AI助手】新增创建用户和查询用户的工具扩展
 * @Description: jeecg llm工具提供者
 * @Author: chenrui
 * @Date: 2025/8/26 18:06
 */
@Slf4j
@Component
public class JeecgBizToolsProvider implements JeecgToolsProvider {

    @Autowired
    SysUserMapper userMapper;

    @Autowired
    private BaseCommonService baseCommonService;

    @Autowired
    private org.jeecg.modules.system.service.ISysRoleService sysRoleService;

    @Autowired
    private org.jeecg.modules.system.service.ISysUserRoleService sysUserRoleService;

    @Autowired
    private org.jeecg.modules.system.service.ISysUserService sysUserService;

    @Autowired
    private IAiragKnowledgeService airagKnowledgeService;

    @Autowired
    private IAiragKnowledgeDocService airagKnowledgeDocService;

    @Autowired
    private EmbeddingHandler embeddingHandler;

    public Map<ToolSpecification, ToolExecutor> getDefaultTools() {
       log.info("--------【AI工具】创建用户、角色和知识库工具扩展------------------");
        Map<ToolSpecification, ToolExecutor> tools = new HashMap<>();

        // 改为从当前 HTTP 请求的 JWT 解析用户名，再按 userId 查权限码集合用于鉴权。
        Set<String> perms = currentUserPermissions();
        if (perms.isEmpty()) {
            // 匿名访问或登录态无效，不暴露任何业务工具
            log.info("【AI工具】当前为匿名/未登录会话，跳过所有业务工具注册");
            return tools;
        }

        if (perms.contains("system:user:listAll")) {
            // 查询所有用户工具
            JeecgLlmTools userTool = queryUserTool(perms);
            tools.put(userTool.getToolSpecification(), userTool.getToolExecutor());
        } else {
            log.info("【AI工具】当前用户无 system:user:listAll 权限，跳过 query_user_by_name 工具注册");
        }

        if (perms.contains("system:user:add")) {
            // 添加用户工具
            JeecgLlmTools addUser = addUserTool(perms);
            tools.put(addUser.getToolSpecification(), addUser.getToolExecutor());
        } else {
            log.info("【AI工具】当前用户无 system:user:add 权限，跳过 add_user 工具注册");
        }

        if (perms.contains("system:role:list")) {
            // 查询所有角色
            JeecgLlmTools queryRoles = queryAllRolesTool(perms);
            tools.put(queryRoles.getToolSpecification(), queryRoles.getToolExecutor());
        } else {
            log.info("【AI工具】当前用户无 system:role:list 权限，跳过 query_all_roles 工具注册");
        }

        if (perms.contains("system:user:addUserRole")) {
            // 给用户授予角色
            JeecgLlmTools grantRoles = grantUserRolesTool(perms);
            tools.put(grantRoles.getToolSpecification(), grantRoles.getToolExecutor());
        } else {
            log.info("【AI工具】当前用户无 system:user:addUserRole 权限，跳过 grant_user_roles 工具注册");
        }

        if (perms.contains("airag:knowledge:list")) {
            JeecgLlmTools listKnowledge = listKnowledgeTool(perms);
            tools.put(listKnowledge.getToolSpecification(), listKnowledge.getToolExecutor());
            JeecgLlmTools queryKnowledge = queryKnowledgeTool(perms);
            tools.put(queryKnowledge.getToolSpecification(), queryKnowledge.getToolExecutor());
        } else {
            log.info("【AI工具】当前用户无 airag:knowledge:list 权限，跳过知识库工具注册");
        }

        return tools;
    }

    /**
     * 解析当前 HTTP 请求 JWT 对应用户的权限码集合。
     * 因为 /airag/chat/send 入口标了 @IgnoreAuth，Shiro 过滤器被跳过，
     * 当前线程的 Subject 是匿名的，无法用 SecurityUtils.getSubject().isPermitted(...) 鉴权。
     *
     * @return 当前登录用户的权限码集合；未登录、token 无效或解析失败时返回空集合
     */
    private Set<String> currentUserPermissions() {
        try {
            HttpServletRequest req = SpringContextUtils.getHttpServletRequest();
            if (req == null) {
                log.info("【AI工具】未获取到当前 HTTP 请求上下文，按匿名处理（无业务工具权限）");
                return Collections.emptySet();
            }
            String token = req.getHeader("X-Access-Token");
            if (StringUtils.isBlank(token)) {
                log.info("【AI工具】请求头缺少 X-Access-Token，按匿名处理（无业务工具权限）");
                return Collections.emptySet();
            }
            String username = JwtUtil.getUsername(token);
            if (StringUtils.isBlank(username)) {
                log.info("【AI工具】X-Access-Token 解析不到 username，按匿名处理（无业务工具权限）");
                return Collections.emptySet();
            }
            SysUser user = sysUserService.getUserByName(username);
            if (user == null) {
                log.warn("【AI工具】token 中的用户在系统中不存在: username={}，按匿名处理", username);
                return Collections.emptySet();
            }
            Set<String> perms = sysUserService.getUserPermissionsSet(user.getId());
            if (perms == null || perms.isEmpty()) {
                log.info("【AI工具】用户无任何权限码: username={}, userId={}", username, user.getId());
                return Collections.emptySet();
            }
            log.info("【AI工具】当前用户权限解析完成: username={}, perms.size={}", username, perms.size());
            return perms;
        } catch (Exception e) {
            log.warn("【AI工具】解析当前用户权限失败: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 添加用户
     * @return
     * @author chenrui
     * @date 2025/8/27 09:51
     */
    private JeecgLlmTools addUserTool(Set<String> perms){
        log.info("--------【AI工具】添加用户工具------------------");
        ToolSpecification toolSpecification = ToolSpecification.builder()
                .name("add_user")
                .description("添加用户,返回添加结果;" +
                        "\n\n - 缺少必要字段时,请向用户索要." +
                        "\n\n - 你应该提前判断用户的输入是否合法,比如用户名是否符合规范,手机号和邮箱是否正确等." +
                        "\n\n - 提前使用用户名查询用户是否存在,如果存在则不能添加." +
                        "\n\n - 添加成功后返回成功消息,如果失败则返回失败原因." +
                        "\n\n - 用户名,手机号均要求唯一,提前通过查询用户工具确认唯一性." )
                .parameters(
                        JsonObjectSchema.builder()
                                .addStringProperty("username", "用户名,必填,只允许使用字母、数字、下划线，且必须以字母开头,唯一")
                                .addStringProperty("password", "用户密码,必填")
                                .addStringProperty("realname", "真实姓名,必填")
                                //.addStringProperty("email", "邮箱,必填,唯一")
                                .addStringProperty("phone", "手机号,必填,唯一")
                                .required("username","password","realname","phone")
                                .build()
                )
                .build();

        // 鉴权改用调用方传入的权限集合（来自 JWT→userId→sysUserService.getUserPermissionsSet）
        final boolean hasAddPermission = perms != null && perms.contains("system:user:add");

        ToolExecutor toolExecutor = (toolExecutionRequest, memoryId) -> {
            // 权限校验（使用提前捕获的结果，避免在异步线程中调用 Shiro）
            if (!hasAddPermission) {
                log.warn("【AI工具】add_user 调用被拒绝：当前用户无 system:user:add 权限");
                return "无权限：您没有添加用户的权限（system:user:add）";
            }
            JSONObject arguments = JSONObject.parseObject(toolExecutionRequest.arguments());
            arguments.put("confirmPassword",arguments.get("password"));
            arguments.put("userIdentity",1);
            arguments.put("activitiSync",1);
            arguments.put("departIds","");
            String selectedRoles = arguments.getString("selectedroles");
            String selectedDeparts = arguments.getString("selecteddeparts");
            String msg = "添加用户失败";
            try {
                SysUser user = JSON.parseObject(arguments.toJSONString(), SysUser.class);
                user.setCreateTime(new Date());//设置创建时间
                String salt = oConvertUtils.randomGen(8);
                user.setSalt(salt);
                String passwordEncode = PasswordUtil.encrypt(user.getUsername(), user.getPassword(), salt);
                user.setPassword(passwordEncode);
                user.setStatus(1);
                user.setDelFlag(CommonConstant.DEL_FLAG_0);
                //用户表字段org_code不能在这里设置他的值
                user.setOrgCode(null);
                // 保存用户走一个service 保证事务
                //获取租户ids
                String relTenantIds = arguments.getString("relTenantIds");
                sysUserService.saveUser(user, selectedRoles, selectedDeparts, relTenantIds, false);
                baseCommonService.addLog("添加用户，username： " +user.getUsername() ,CommonConstant.LOG_TYPE_2, 2);
                msg = "添加用户成功";
                // 用户变更，触发同步工作流
            } catch (Exception e) {
                e.printStackTrace();
                msg = "添加用户失败";
            }
            return msg;
        };
        return new JeecgLlmTools(toolSpecification,toolExecutor);
    }

    /**
     * 查询用户信息
     *
     * @return 用户列表JSON字符串
     * @author chenrui
     * @date 2025/8/26 18:52
     */
    private JeecgLlmTools queryUserTool(Set<String> perms) {
        ToolSpecification toolSpecification = ToolSpecification.builder()
                .name("query_user_by_name")
                .description("查询用户详细信息，返回json数组。支持用户名、真实姓名、邮箱、手机号 多字段组合查询，用户名、真实姓名、邮箱、手机号均为模糊查询。无条件则返回全部用户。")
                .parameters(
                        JsonObjectSchema.builder()
                                .addStringProperty("username", "用户名")
                                .addStringProperty("realname", "真实姓名")
                                .addStringProperty("email", "电子邮件")
                                .addStringProperty("phone", "手机号")
                                .build()
                )
                .build();

        // 鉴权改用调用方传入的权限集合
        final boolean hasListPermission = perms != null && perms.contains("system:user:listAll");

        ToolExecutor toolExecutor = (toolExecutionRequest, memoryId) -> {
            // 权限校验（使用提前捕获的结果，避免在异步线程中调用 Shiro）
            if (!hasListPermission) {
                log.warn("【AI工具】query_user_by_name 调用被拒绝：当前用户无 system:user:listAll 权限");
                return "无权限：您没有查询用户列表的权限";
            }
            SysUser args = JSONObject.parseObject(toolExecutionRequest.arguments(), SysUser.class);
            QueryWrapper<SysUser> qw = new QueryWrapper<>();
            if (StringUtils.isNotBlank(args.getUsername())) {
                qw.like("username", args.getUsername());
            }
            if (StringUtils.isNotBlank(args.getRealname())) {
                qw.like("realname", args.getRealname());
            }
            if (StringUtils.isNotBlank(args.getEmail())) {
                qw.like("email", args.getEmail());
            }
            if (StringUtils.isNotBlank(args.getPhone())) {
                qw.like("phone", args.getPhone());
            }
            if (StringUtils.isNotBlank(args.getWorkNo())) {
                qw.eq("work_no", args.getWorkNo());
            }
            qw.eq("del_flag", 0);
            List<SysUser> users = userMapper.selectList(qw);
            users.forEach(u -> { u.setPassword(null); u.setSalt(null); });
            return JSONObject.toJSONString(users);
        };
        return new JeecgLlmTools(toolSpecification, toolExecutor);
    }

    /**
     * 查询所有角色
     * @return
     * @author chenrui
     * @date 2025/8/27 09:52
     */
    private JeecgLlmTools queryAllRolesTool(Set<String> perms) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("query_all_roles")
                .description("查询所有角色，返回json数组。包含字段：id、roleName、roleCode；默认按创建时间/排序号规则由后端决定。")
                .parameters(
                        JsonObjectSchema.builder()
                                .addStringProperty("roleName", "角色姓名")
                                .addStringProperty("roleCode", "角色编码")
                                .build()
                )
                .build();

        // 鉴权改用调用方传入的权限集合
        final boolean hasRoleListPermission = perms != null && perms.contains("system:role:list");

        ToolExecutor exec = (toolExecutionRequest, memoryId) -> {
            // 权限校验（使用提前捕获的结果，避免在异步线程中调用 Shiro）
            if (!hasRoleListPermission) {
                log.warn("【AI工具】query_all_roles 调用被拒绝：当前用户无 system:role:list 权限");
                return "无权限：您没有查询角色列表的权限";
            }
            // 做租户隔离查询（若开启）
            SysRole sysRole = JSONObject.parseObject(toolExecutionRequest.arguments(), SysRole.class);
            QueryWrapper<SysRole> qw = Wrappers.query();
            if (StringUtils.isNotBlank(sysRole.getRoleName())) {
                qw.like("role_name", sysRole.getRoleName());
            }
            if (StringUtils.isNotBlank(sysRole.getRoleCode())) {
                qw.like("role_code", sysRole.getRoleCode());
            }
            // 未删除
            List<SysRole> roles = sysRoleService.list(qw);
            // 仅返回核心字段
            JSONArray arr = new JSONArray();
            for (SysRole r : roles) {
                JSONObject o = new JSONObject();
                o.put("id", r.getId());
                o.put("roleName", r.getRoleName());
                o.put("roleCode", r.getRoleCode());
                arr.add(o);
            }
            return arr.toJSONString();
        };
        return new JeecgLlmTools(spec, exec);
    }

    /**
     * 给用户授予角色
     * @return
     * @author chenrui
     * @date 2025/8/27 09:52
     */
    private JeecgLlmTools grantUserRolesTool(Set<String> perms) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("grant_user_roles")
                .description("给用户授予角色，支持一次授予多个角色；如果关系已存在则跳过。返回授予结果统计。")
                .parameters(
                        JsonObjectSchema.builder()
                                .addStringProperty("userId", "用户ID，必填")
                                .addStringProperty("roleIds", "角色ID列表，必填，使用英文逗号分隔")
                                .required("userId","roleIds")
                                .build()
                )
                .build();

        // 鉴权改用调用方传入的权限集合
        final boolean hasGrantPermission = perms != null
                && (perms.contains("system:user:addUserRole") || perms.contains("system:user:edit"));

        ToolExecutor exec = (toolExecutionRequest, memoryId) -> {
            // 权限校验（使用提前捕获的结果，避免在异步线程中调用 Shiro）
            if (!hasGrantPermission) {
                log.warn("【AI工具】grant_user_roles 调用被拒绝：当前用户无 system:user:addUserRole 或 system:user:edit 权限");
                return "无权限：您没有给用户授予角色的权限";
            }
            JSONObject args = JSONObject.parseObject(toolExecutionRequest.arguments());
            String userId = args.getString("userId");
            String roleIdsStr = args.getString("roleIds");
            if (StringUtils.isAnyBlank(userId, roleIdsStr)) {
                return "参数缺失：userId 或 roleIds";
            }
            SysUser user = sysUserService.getById(userId);
            if (user == null) {
                return "用户不存在：" + userId;
            }
            String[] roleIds = roleIdsStr.split(",");
            int added = 0, existed = 0, invalid = 0;
            for (String roleId : roleIds) {
                roleId = roleId.trim();
                if (roleId.isEmpty()) continue;
                SysRole role = sysRoleService.getById(roleId);
                if (role == null) { invalid++; continue; }
                QueryWrapper<org.jeecg.modules.system.entity.SysUserRole> q = new QueryWrapper<>();
                q.eq("role_id", roleId).eq("user_id", userId);
                org.jeecg.modules.system.entity.SysUserRole one = sysUserRoleService.getOne(q);
                if (one == null) {
                    org.jeecg.modules.system.entity.SysUserRole rel = new org.jeecg.modules.system.entity.SysUserRole(userId, roleId);
                    boolean ok = sysUserRoleService.save(rel);
                    if (ok) { added++; } else { invalid++; }
                } else {
                    existed++;
                }
            }
            return String.format("授予完成：新增%d，已存在%d，无效/失败%d", added, existed, invalid);
        };
        return new JeecgLlmTools(spec, exec);
    }

    /**
     * 列出知识库及其文档标题。
     */
    private JeecgLlmTools listKnowledgeTool(Set<String> perms) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("list_knowledge")
                .description("列出知识库及其中的文档标题。用户询问有哪些知识库、知识库里有什么、列出知识库内容时必须调用本工具，禁止编造。"
                        + "可按名称模糊筛选；不填名称则列出全部知识库（不含记忆库）。"
                        + "返回知识库 id、名称、描述、状态、文档数量和文档标题。正文片段请改用 query_knowledge。")
                .parameters(
                        JsonObjectSchema.builder()
                                .addStringProperty("name", "知识库名称，可选，模糊匹配")
                                .build()
                )
                .build();
        final boolean allowed = perms != null && perms.contains("airag:knowledge:list");
        ToolExecutor exec = (toolExecutionRequest, memoryId) -> {
            if (!allowed) {
                return "无权限：您没有查询知识库的权限（airag:knowledge:list）";
            }
            try {
                JSONObject args = JSONObject.parseObject(toolExecutionRequest.arguments());
                String name = args == null ? null : args.getString("name");
                List<AiragKnowledge> knowledges = findKnowledges(name, false);
                if (knowledges.isEmpty()) {
                    return StringUtils.isBlank(name) ? "当前没有可用的知识库。" : "未找到名称包含「" + name.trim() + "」的知识库。";
                }
                JSONArray arr = new JSONArray();
                for (AiragKnowledge knowledge : knowledges) {
                    JSONObject item = new JSONObject();
                    item.put("id", knowledge.getId());
                    item.put("name", knowledge.getName());
                    item.put("descr", knowledge.getDescr());
                    item.put("status", knowledge.getStatus());
                    List<String> titles = listDocTitles(knowledge.getId(), 20);
                    long docCount = airagKnowledgeDocService.count(new LambdaQueryWrapper<AiragKnowledgeDoc>()
                            .eq(AiragKnowledgeDoc::getKnowledgeId, knowledge.getId()));
                    item.put("docCount", docCount);
                    item.put("documents", titles);
                    if (docCount > titles.size()) {
                        item.put("documentsTruncated", true);
                    }
                    arr.add(item);
                }
                return arr.toJSONString();
            } catch (Exception e) {
                log.warn("【AI工具】list_knowledge 失败: {}", e.getMessage());
                return "列出知识库失败：" + e.getMessage();
            }
        };
        return new JeecgLlmTools(spec, exec);
    }

    /**
     * 按问题检索知识库正文片段。
     */
    private JeecgLlmTools queryKnowledgeTool(Set<String> perms) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("query_knowledge")
                .description("在知识库中按问题检索正文片段。用户询问知识库里的具体内容、事实、报告细节时必须调用本工具，并只依据返回片段回答，禁止编造。"
                        + "queryText 必填；knowledgeName 可选，不填则在已启用的知识库中检索（不含记忆库）。"
                        + "若只要知识库或文档清单，请改用 list_knowledge。")
                .parameters(
                        JsonObjectSchema.builder()
                                .addStringProperty("queryText", "要检索的问题或关键词，必填")
                                .addStringProperty("knowledgeName", "知识库名称，可选，模糊匹配")
                                .addStringProperty("topNumber", "返回条数，可选，默认5，最大8")
                                .required("queryText")
                                .build()
                )
                .build();
        final boolean allowed = perms != null && perms.contains("airag:knowledge:list");
        ToolExecutor exec = (toolExecutionRequest, memoryId) -> {
            if (!allowed) {
                return "无权限：您没有查询知识库的权限（airag:knowledge:list）";
            }
            try {
                JSONObject args = JSONObject.parseObject(toolExecutionRequest.arguments());
                String queryText = args == null ? null : args.getString("queryText");
                if (StringUtils.isBlank(queryText)) {
                    return "参数缺失：queryText";
                }
                String knowledgeName = args.getString("knowledgeName");
                int topNumber = 5;
                if (StringUtils.isNotBlank(args.getString("topNumber"))) {
                    try {
                        topNumber = Integer.parseInt(args.getString("topNumber").trim());
                    } catch (NumberFormatException ignored) {
                        topNumber = 5;
                    }
                }
                if (topNumber < 1) {
                    topNumber = 1;
                }
                if (topNumber > 8) {
                    topNumber = 8;
                }
                List<AiragKnowledge> knowledges = findKnowledges(knowledgeName, true);
                if (knowledges.isEmpty()) {
                    return StringUtils.isBlank(knowledgeName)
                            ? "没有已启用的知识库可检索。"
                            : "未找到名称包含「" + knowledgeName.trim() + "」的已启用知识库。";
                }
                boolean limited = knowledges.size() > 8;
                if (limited) {
                    knowledges = knowledges.subList(0, 8);
                }
                List<Map<String, Object>> hits = new ArrayList<>();
                List<String> errors = new ArrayList<>();
                for (AiragKnowledge knowledge : knowledges) {
                    try {
                        List<Map<String, Object>> part = embeddingHandler.searchEmbedding(knowledge.getId(), queryText.trim(), topNumber, null);
                        if (part == null) {
                            continue;
                        }
                        for (Map<String, Object> hit : part) {
                            Map<String, Object> row = new HashMap<>();
                            row.put("knowledgeName", knowledge.getName());
                            row.put("docName", hit.get(EmbeddingHandler.EMBED_STORE_METADATA_DOCNAME));
                            row.put("score", hit.get("score"));
                            String content = oConvertUtils.getString(hit.get("content"), "");
                            if (content.length() > 800) {
                                content = content.substring(0, 800) + "...";
                            }
                            row.put("content", content);
                            hits.add(row);
                        }
                    } catch (Exception ex) {
                        log.warn("【AI工具】query_knowledge 检索失败 knowId={}: {}", knowledge.getId(), ex.getMessage());
                        errors.add(knowledge.getName() + "：" + ex.getMessage());
                    }
                }
                hits.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
                if (hits.size() > topNumber) {
                    hits = hits.subList(0, topNumber);
                }
                JSONObject out = new JSONObject();
                out.put("hits", hits);
                if (hits.isEmpty()) {
                    out.put("message", "未检索到相关片段。");
                }
                if (limited) {
                    out.put("notice", "知识库超过8个，仅检索了最近的8个。请用 knowledgeName 指定知识库。");
                }
                if (!errors.isEmpty() && hits.isEmpty()) {
                    out.put("errors", errors);
                }
                return out.toJSONString();
            } catch (Exception e) {
                log.warn("【AI工具】query_knowledge 失败: {}", e.getMessage());
                return "检索知识库失败：" + e.getMessage();
            }
        };
        return new JeecgLlmTools(spec, exec);
    }

    private List<AiragKnowledge> findKnowledges(String name, boolean enabledOnly) {
        LambdaQueryWrapper<AiragKnowledge> qw = new LambdaQueryWrapper<>();
        qw.and(w -> w.ne(AiragKnowledge::getType, LLMConsts.KNOWLEDGE_TYPE_MEMORY).or().isNull(AiragKnowledge::getType));
        if (enabledOnly) {
            qw.eq(AiragKnowledge::getStatus, LLMConsts.STATUS_ENABLE);
        }
        if (StringUtils.isNotBlank(name)) {
            qw.like(AiragKnowledge::getName, name.trim());
        }
        qw.orderByDesc(AiragKnowledge::getCreateTime);
        qw.last("LIMIT 30");
        List<AiragKnowledge> list = airagKnowledgeService.list(qw);
        return list == null ? Collections.emptyList() : list;
    }

    private List<String> listDocTitles(String knowledgeId, int limit) {
        LambdaQueryWrapper<AiragKnowledgeDoc> qw = new LambdaQueryWrapper<>();
        qw.select(AiragKnowledgeDoc::getTitle);
        qw.eq(AiragKnowledgeDoc::getKnowledgeId, knowledgeId);
        qw.orderByDesc(AiragKnowledgeDoc::getCreateTime);
        qw.last("LIMIT " + limit);
        List<AiragKnowledgeDoc> docs = airagKnowledgeDocService.list(qw);
        List<String> titles = new ArrayList<>();
        if (docs == null) {
            return titles;
        }
        for (AiragKnowledgeDoc doc : docs) {
            if (doc != null && StringUtils.isNotBlank(doc.getTitle())) {
                titles.add(doc.getTitle());
            }
        }
        return titles;
    }

    private static double scoreOf(Map<String, Object> hit) {
        Object score = hit.get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        return 0D;
    }
}
