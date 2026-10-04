package org.jeecg.modules.airag.shreport.map;

import com.alibaba.fastjson.JSONObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 山洪一张图数据后台维护：流域 / 断面 / 防治对象 / 隐患要素 / 调查成果表
 * 的分页查询、行编辑、删除与按调查年份（survey_year）的 Excel 导入。
 */
@Slf4j
@Tag(name = "山洪报告-一张图数据维护")
@RestController
@RequestMapping("/sh/report/map/admin")
public class ShReportMapAdminController {

    @Autowired
    private ShReportMapAdminService adminService;

    @Autowired
    private ShReportMapImportService importService;

    @Autowired
    private ShReportKnowledgeGraphService knowledgeGraphService;

    @Operation(summary = "数据集清单（含列配置，供动态表格与导入模板）")
    @GetMapping("/datasets")
    public Result<List<Map<String, Object>>> datasets() {
        return Result.OK(adminService.datasets());
    }

    @Operation(summary = "数据集的调查年份清单（year=0 表示未标注）")
    @GetMapping("/years")
    public Result<?> years(@RequestParam String ds) {
        try {
            return Result.OK(adminService.years(ds));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "数据集筛选项（调查成果：政区县/乡镇与流域去重清单）")
    @GetMapping("/facets")
    public Result<?> facets(@RequestParam String ds) {
        try {
            return Result.OK(adminService.facets(ds));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "山洪知识图谱：不传 wscd 为对象关系总览；传 wscd 展开该小流域；q 为名称检索")
    @GetMapping("/kg")
    public Result<Map<String, Object>> knowledgeGraph(@RequestParam(required = false) String wscd,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(required = false) Integer year) {
        try {
            return Result.OK(knowledgeGraphService.graph(wscd, q, year));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("山洪知识图谱查询失败", e);
            return Result.error("知识图谱查询失败：" + e.getMessage());
        }
    }

    @Operation(summary = "按主键读一行")
    @GetMapping("/row")
    public Result<?> row(@RequestParam String ds, @RequestParam Long id) {
        try {
            return Result.OK(adminService.getRow(ds, id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "分页列表（year 传 0 表示未标注，不传表示全部；countyAdcd/townAdcd/wscd 政区与流域过滤）")
    @GetMapping("/list")
    public Result<Map<String, Object>> list(@RequestParam String ds,
                                            @RequestParam(required = false) Integer year,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(required = false) String countyAdcd,
                                            @RequestParam(required = false) String townAdcd,
                                            @RequestParam(required = false) String wscd,
                                            @RequestParam(defaultValue = "1") int pageNo,
                                            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            return Result.OK(adminService.list(ds, year, search, countyAdcd, townAdcd, wscd, pageNo, pageSize));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "编辑一行（data 键值必须属于该数据集的白名单列，可含 survey_year）")
    @PostMapping("/row/update")
    public Result<String> updateRow(@RequestBody JSONObject body) {
        try {
            String ds = body.getString("ds");
            Long id = body.getLong("id");
            if (id == null) {
                return Result.error("id 不能为空");
            }
            Map<String, Object> data = body.getJSONObject("data");
            adminService.updateRow(ds, id, data);
            return Result.OK("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("一张图数据行编辑失败", e);
            return Result.error("保存失败：" + e.getMessage());
        }
    }

    @Operation(summary = "删除行（支持批量 ids）")
    @PostMapping("/row/delete")
    public Result<?> deleteRows(@RequestBody JSONObject body) {
        try {
            String ds = body.getString("ds");
            List<Object> ids = body.getObject("ids", List.class);
            int n = adminService.deleteRows(ds, ids);
            return Result.OK("已删除 " + n + " 行");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("一张图数据行删除失败", e);
            return Result.error("删除失败：" + e.getMessage());
        }
    }

    @Operation(summary = "读取行空间几何（GeoJSON geometry，geom 为空时返回 null）")
    @GetMapping("/geom")
    public Result<?> getGeometry(@RequestParam String ds, @RequestParam Long id) {
        try {
            return Result.OK(adminService.getGeometry(ds, id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("一张图几何读取失败 ds={} id={}", ds, id, e);
            return Result.error("读取几何失败：" + e.getMessage());
        }
    }

    @Operation(summary = "保存行空间几何（GeoJSON geometry；hazard 同步回写经纬度）")
    @PostMapping("/geom/update")
    public Result<String> updateGeometry(@RequestBody JSONObject body) {
        try {
            String ds = body.getString("ds");
            Long id = body.getLong("id");
            if (id == null) {
                return Result.error("id 不能为空");
            }
            Map<String, Object> geometry = body.getJSONObject("geometry");
            adminService.updateGeometry(ds, id, geometry);
            return Result.OK("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("一张图几何保存失败", e);
            return Result.error("保存几何失败：" + e.getMessage());
        }
    }

    @Operation(summary = "按编码查关联的空间几何（如调查成果按 15 位政区编码关联防治对象面）")
    @GetMapping("/geom/by-pid")
    public Result<?> findGeometryByPid(@RequestParam String ds, @RequestParam String pid) {
        try {
            return Result.OK(adminService.findGeometryByPid(ds, pid));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("按编码查几何失败 ds={} pid={}", ds, pid, e);
            return Result.error("查询失败：" + e.getMessage());
        }
    }

    @Operation(summary = "Excel 导入（xlsx；按调查年份入库；mode=append 追加 / replace 覆盖该年份）")
    @PostMapping("/import")
    public Result<Map<String, Object>> importExcel(@RequestParam String ds,
                                                   @RequestParam int year,
                                                   @RequestParam(defaultValue = "append") String mode,
                                                   @RequestParam("file") MultipartFile file) {
        try {
            int[] r = importService.importExcel(ds, year, mode, file);
            return Result.OK(Map.of("imported", r[0], "skipped", r[1]));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("一张图数据 Excel 导入失败 ds={}", ds, e);
            return Result.error("导入失败：" + e.getMessage());
        }
    }
}
