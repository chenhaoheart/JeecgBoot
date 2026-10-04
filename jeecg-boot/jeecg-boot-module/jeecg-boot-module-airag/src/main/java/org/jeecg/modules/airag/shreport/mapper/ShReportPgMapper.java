package org.jeecg.modules.airag.shreport.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 一张图调查成果 PostgreSQL（shreport_map 库）固定形状查询。
 * <p>
 * 数据源在 spring.datasource.dynamic.datasource.shreport 中配置，@DS 路由；
 * SQL 统一放在同目录 xml/ShReportPgMapper.xml。结果为 LinkedHashMap，保持 SQL 列顺序。
 * 按条件动态拼装的查询不在此处（见 ShReportPgQueryService）。
 */
@DS("shreport")
public interface ShReportPgMapper {

    // ---------------------------------------------------------------- 数据包（按流域编码 wscd）

    /** 流域基本信息：全称/简称/水系/面积/周长/坡度/高程/集镇防治村统计 */
    List<Map<String, Object>> selectWatershedInfo(@Param("wscd") String wscd);

    /** 防治对象总量：数量 c、受威胁人口 p（指标锁定用） */
    Map<String, Object> selectObjectCountSum(@Param("wscd") String wscd);

    /** 防治对象总量：数量/人口/涉及乡镇数（数据包用） */
    List<Map<String, Object>> selectObjectTotals(@Param("wscd") String wscd);

    /** 防治对象按类型统计 */
    List<Map<String, Object>> selectObjectTypes(@Param("wscd") String wscd);

    /** 按乡镇分布 */
    List<Map<String, Object>> selectTowns(@Param("wscd") String wscd);

    /** 重点防治对象（按受威胁人口倒序，最多 30 条） */
    List<Map<String, Object>> selectKeyObjects(@Param("wscd") String wscd);

    /** 防治对象标注的风险隐患要素统计（risk_flags 展开） */
    List<Map<String, Object>> selectRiskFlags(@Param("wscd") String wscd);

    /** 沟道及沟滩占地调查成果（数值列为字符型，安全转 numeric） */
    List<Map<String, Object>> selectGullyOccupy(@Param("wscd") String wscd);

    /** 隐患要素点位按类型统计 */
    List<Map<String, Object>> selectHazardStats(@Param("wscd") String wscd);

    /** 隐患要素点位清单（最多 30 条） */
    List<Map<String, Object>> selectHazardPoints(@Param("wscd") String wscd);

    /** 跨沟桥涵隐患点（含编码，供现场照片列；最多 20 条） */
    List<Map<String, Object>> selectBridgeHazards(@Param("wscd") String wscd);

    /** 跨沟道路桥涵坝阻水情况（按阻水比 R1 倒序，最多 30 条） */
    List<Map<String, Object>> selectRoadObstruction(@Param("wscd") String wscd);

    /** 防治区（防护对象面）按类型统计 */
    List<Map<String, Object>> selectPreventionAreas(@Param("wscd") String wscd);

    // ---------------------------------------------------------------- 数据问答（全域/绑定流域）

    /** 绑定流域概况：流域名、对象数、人口、有风险隐患对象数 */
    Map<String, Object> selectBoundOverview(@Param("wscd") String wscd);

    /** 全域概况：小流域数、防治对象数、涉及流域数、人口 */
    Map<String, Object> selectGlobalOverview();

    /** 桥涵坝阻水比 R1 分级统计（全域） */
    List<Map<String, Object>> selectRoadObstructionGrade();

    /** 高阻水桥涵坝清单（R1>30，最多 20 条） */
    List<Map<String, Object>> selectRoadObstructionHigh();

    /** 沟滩占地统计（全域） */
    List<Map<String, Object>> selectGullyStats();

    /** 小流域清单（keyword 可空：全称/别名/编码模糊匹配） */
    List<Map<String, Object>> selectWatershedList(@Param("keyword") String keyword);

    // ---------------------------------------------------------------- 流域清单 / 检索

    /** 后台流域清单：全部流域 + 防治对象数/人口（无对象的流域也列出） */
    List<Map<String, Object>> selectWatershedObjectCounts();

    /** 编码查流域（返回 wsnm / alias_name 原始列） */
    Map<String, Object> selectWatershedByCode(@Param("wscd") String wscd);

    /** 全部流域原始列（LCS 相似度推荐用） */
    List<Map<String, Object>> selectAllWatersheds();

    /** 按名称排序的流域候选（LIMIT 传入） */
    List<Map<String, Object>> selectWatershedsOrdered(@Param("limit") int limit);

    /** 精确匹配：wscd / wsnm / alias_name 任一相等 */
    List<Map<String, Object>> selectWatershedsExact(@Param("key") String key);

    /** 模糊匹配：全称含完整关键词，或全称/别名含去「流域」后缀的短词 */
    List<Map<String, Object>> selectWatershedsLike(@Param("fullKey") String fullKey, @Param("shortKey") String shortKey);

    /** 反向包含：用户输入包含完整流域全称 */
    List<Map<String, Object>> selectWatershedsContaining(@Param("key") String key);

    /** 批量生成用流域列表（wscds 可空：空为全部，按 wscd 排序） */
    List<Map<String, Object>> selectWatershedsByCodes(@Param("wscds") List<String> wscds);
}
