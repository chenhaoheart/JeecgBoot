package org.jeecg.modules.airag.shreport.map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 山洪一张图：流域/政区筛选、断面/防治对象/隐患要素空间数据（PostGIS）。
 */
@Slf4j
@Tag(name = "山洪报告-一张图")
@RestController
@RequestMapping("/sh/report/map")
public class ShReportMapController {

    @Autowired
    private ShReportMapService mapService;

    @Autowired
    private ShHazardAiRiskService hazardAiRiskService;

    @Operation(summary = "地图配置（天地图 key / 默认中心）")
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.OK(mapService.config());
    }

    @Operation(summary = "筛选选项：流域 / 县 / 乡镇")
    @GetMapping("/filters")
    public Result<Map<String, Object>> filters(@RequestParam(required = false) String countyAdcd) {
        return Result.OK(mapService.filters(countyAdcd));
    }

    @Operation(summary = "图层 GeoJSON：watershed | section | prevention | hazard")
    @GetMapping("/geojson")
    public Result<Map<String, Object>> geojson(@RequestParam String layer,
                                               @RequestParam(required = false) String wscd,
                                               @RequestParam(required = false) String adcd,
                                               @RequestParam(required = false) String types,
                                               @RequestParam(required = false) String bbox) {
        try {
            return Result.OK(mapService.geojson(layer, wscd, adcd, types, bbox));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "防治对象分页列表")
    @GetMapping("/prevention")
    public Result<Map<String, Object>> prevention(@RequestParam(defaultValue = "1") int pageNo,
                                                  @RequestParam(defaultValue = "10") int pageSize,
                                                  @RequestParam(required = false) String search,
                                                  @RequestParam(required = false) String wscd,
                                                  @RequestParam(required = false) String adcd) {
        return Result.OK(mapService.listPrevention(pageNo, pageSize, search, wscd, adcd));
    }

    @Operation(summary = "防治对象详情")
    @GetMapping("/prevention/{id}")
    public Result<Map<String, Object>> preventionDetail(@PathVariable String id) {
        Map<String, Object> row = mapService.getPrevention(id);
        return row == null ? Result.error("防治对象不存在") : Result.OK(row);
    }

    @Operation(summary = "隐患要素分页列表（types 逗号分隔的隐患类型）")
    @GetMapping("/hazards")
    public Result<Map<String, Object>> hazards(@RequestParam(defaultValue = "1") int pageNo,
                                               @RequestParam(defaultValue = "10") int pageSize,
                                               @RequestParam(required = false) String search,
                                               @RequestParam(required = false) String wscd,
                                               @RequestParam(required = false) String adcd,
                                               @RequestParam(required = false) String types) {
        return Result.OK(mapService.listHazards(pageNo, pageSize, search, wscd, adcd, types));
    }

    @Operation(summary = "隐患要素详情（自动关联调查成果）")
    @GetMapping("/hazards/{id}")
    public Result<Map<String, Object>> hazardDetail(@PathVariable String id) {
        Map<String, Object> row = mapService.getHazard(id);
        return row == null ? Result.error("隐患要素不存在") : Result.OK(row);
    }

    @Operation(summary = "按隐患点编码取详情（与地图预览同一份数据）")
    @GetMapping("/hazard")
    public Result<Map<String, Object>> hazardByPid(@RequestParam String pid) {
        Map<String, Object> row = mapService.getHazardByPid(pid);
        return row == null ? Result.error("隐患要素不存在") : Result.OK(row);
    }

    @Operation(summary = "隐患点：根据现场照片和外业备注识别风险（不回写数据，写入台账）")
    @PostMapping("/hazards/{id}/ai-risk")
    public Result<Map<String, Object>> hazardAiRisk(@PathVariable String id) {
        try {
            return Result.OK(hazardAiRiskService.analyze(id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("隐患点风险识别失败 id={}", id, e);
            String msg = e.getMessage();
            return Result.error(msg == null || msg.isBlank() ? "识别失败" : msg);
        }
    }

    @Operation(summary = "隐患点 AI 识别台账")
    @GetMapping("/ai-risk/logs")
    public Result<Map<String, Object>> hazardAiRiskLogs(@RequestParam(defaultValue = "1") int pageNo,
                                                        @RequestParam(defaultValue = "10") int pageSize,
                                                        @RequestParam(required = false) String search,
                                                        @RequestParam(required = false) String status) {
        return Result.OK(hazardAiRiskService.pageLogs(pageNo, pageSize, search, status));
    }

    @Operation(summary = "隐患点 AI 识别台账详情")
    @GetMapping("/ai-risk/logs/{id}")
    public Result<Map<String, Object>> hazardAiRiskLog(@PathVariable long id) {
        Map<String, Object> row = hazardAiRiskService.getLog(id);
        return row == null ? Result.error("记录不存在") : Result.OK(row);
    }

    @Operation(summary = "断面详情")
    @GetMapping("/section/{id}")
    public Result<Map<String, Object>> sectionDetail(@PathVariable String id) {
        Map<String, Object> row = mapService.getSection(id);
        return row == null ? Result.error("断面不存在") : Result.OK(row);
    }

    @Operation(summary = "断面剖面数据（横断面：起点距-高程；纵断面：距离-河底/水面高程）")
    @GetMapping("/section/profile")
    public Result<Map<String, Object>> sectionProfile(@RequestParam String pid) {
        Map<String, Object> row = mapService.sectionProfile(pid);
        return row == null ? Result.error("断面不存在") : Result.OK(row);
    }

    @Operation(summary = "图层统计")
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.OK(mapService.stats());
    }
}
