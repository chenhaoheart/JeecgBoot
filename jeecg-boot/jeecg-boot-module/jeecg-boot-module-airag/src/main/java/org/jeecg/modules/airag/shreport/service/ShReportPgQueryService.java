package org.jeecg.modules.airag.shreport.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 一张图调查成果 PostgreSQL（shreport_map 库）统一查询入口：数据源在
 * spring.datasource.dynamic.datasource.shreport 中配置，通过 @DS 路由，
 * 复用全局 Druid 连接池，不再单独建池。
 * <p>
 * 注意：@DS 基于代理生效，请从其他 Bean 调用本类方法（不要在本类内部互调）。
 */
@Slf4j
@Service
public class ShReportPgQueryService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DS("shreport")
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }

    /** 单列查询（如 String.class），供章节/指标迁移使用。 */
    @DS("shreport")
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
        return jdbcTemplate.queryForList(sql, elementType, args);
    }

    @DS("shreport")
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return jdbcTemplate.queryForObject(sql, requiredType, args);
    }

    @DS("shreport")
    public int queryInt(String sql, Object... args) {
        Integer v = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }

    @DS("shreport")
    public int update(String sql, Object... args) {
        return jdbcTemplate.update(sql, args);
    }

    @DS("shreport")
    public void execute(String sql) {
        jdbcTemplate.execute(sql);
    }

    /** 流域展示名：别名（alias_name，如「官鹅沟」）优先，补「流域」后缀；否则退回全称 wsnm */
    public static String displayName(Object wsnm, Object alias) {
        String a = alias == null ? "" : String.valueOf(alias).trim();
        if (!a.isEmpty()) {
            return a.endsWith("流域") ? a : a + "流域";
        }
        return wsnm == null ? "" : String.valueOf(wsnm);
    }
}
