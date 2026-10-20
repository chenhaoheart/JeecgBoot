package org.jeecg.modules.airag.onemap.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.onemap.service.ShFileObjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSONObject;

import java.nio.file.Path;

/**
 * 一张图调查成果文件（照片/视频）管理：磁盘目录扫描索引、分页筛选、
 * 手动绑定隐患点与安全预览。预览接口在 shiro excludeUrls 放行
 * （img/video 标签无法携带 token），安全性由「根目录约束 + 扩展名白名单」保证。
 */
@Slf4j
@Tag(name = "山洪报告-一张图文件管理")
@RestController
@RequestMapping("/sh/report/map/file")
public class ShFileObjectController {

    @Autowired
    private ShFileObjectService fileService;

    @Operation(summary = "扫描照片根目录并登记文件对象（按相对路径幂等 upsert）")
    @PostMapping("/scan")
    public Result<?> scan() {
        try {
            return Result.OK(fileService.scan());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("一张图文件扫描失败", e);
            return Result.error("扫描失败：" + e.getMessage());
        }
    }

    @Operation(summary = "文件分页列表（category/filePosition/fileType/riverCode/wsnm/countyAdcd/pid/search/matched）")
    @GetMapping("/list")
    public Result<?> list(@RequestParam(required = false) Integer pageNo,
                          @RequestParam(required = false) Integer pageSize,
                          @RequestParam(required = false) String category,
                          @RequestParam(required = false) String filePosition,
                          @RequestParam(required = false) String fileType,
                          @RequestParam(required = false) String riverCode,
                          @RequestParam(required = false) String wsnm,
                          @RequestParam(required = false) String countyAdcd,
                          @RequestParam(required = false) String pid,
                          @RequestParam(required = false) String search,
                          @RequestParam(required = false) Boolean matched) {
        try {
            return Result.OK(fileService.list(pageNo, pageSize, category, filePosition, fileType,
                    riverCode, wsnm, countyAdcd, pid, search, matched));
        } catch (Exception e) {
            log.error("一张图文件列表查询失败", e);
            return Result.error("查询失败：" + e.getMessage());
        }
    }

    @Operation(summary = "筛选项：类别/流域/县清单")
    @GetMapping("/facets")
    public Result<?> facets() {
        try {
            return Result.OK(fileService.facets());
        } catch (Exception e) {
            log.error("一张图文件筛选项查询失败", e);
            return Result.error("查询失败：" + e.getMessage());
        }
    }

    @Operation(summary = "按隐患点编码取文件（详情照片/视频分组）")
    @GetMapping("/by-pid")
    public Result<?> listByPid(@RequestParam String pid) {
        try {
            return Result.OK(fileService.listByPid(pid));
        } catch (Exception e) {
            log.error("按隐患点取文件失败 pid={}", pid, e);
            return Result.error("查询失败：" + e.getMessage());
        }
    }

    @Operation(summary = "手动绑定文件到隐患点编码（pid 传空解除绑定）")
    @PostMapping("/bind")
    public Result<String> bind(@RequestBody JSONObject body) {
        try {
            Long id = body.getLong("id");
            String pid = body.getString("pid");
            int n = fileService.bind(id, pid);
            return n > 0 ? Result.OK("ok") : Result.error("文件不存在");
        } catch (Exception e) {
            log.error("文件绑定失败", e);
            return Result.error("绑定失败：" + e.getMessage());
        }
    }

    /**
     * 文件预览：<img>/<video> 直接引用，无 token；支持 HTTP Range（视频拖动）。
     * 返回 404/400 + JSON 错误信息，前端据此展示占位。
     */
    @Operation(summary = "文件预览（img/video 直链；防目录穿越 + 扩展名白名单）")
    @GetMapping("/preview")
    public ResponseEntity<org.springframework.core.io.Resource> preview(@RequestParam String path) {
        Path file;
        try {
            file = fileService.resolveForPreview(path);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("文件预览失败 path={}", path, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileService.mediaTypeOf(file)))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(new FileSystemResource(file));
    }
}
