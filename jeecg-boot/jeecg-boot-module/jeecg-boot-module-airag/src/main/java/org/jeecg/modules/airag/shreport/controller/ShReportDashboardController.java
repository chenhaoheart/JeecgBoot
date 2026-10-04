package org.jeecg.modules.airag.shreport.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.shreport.service.ShReportDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 首页与工作台：报告、知识库、流域与隐患要素概览。
 */
@Tag(name = "山洪报告-AI概览")
@RestController
@RequestMapping("/sh/report/dashboard")
public class ShReportDashboardController {

    @Autowired
    private ShReportDashboardService dashboardService;

    @Operation(summary = "AI概览：已生成报告、知识库、流域/隐患/防治对象")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.OK(dashboardService.overview());
    }
}
