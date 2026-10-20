package org.jeecg.modules.airag.shreport.controller;

import com.alibaba.fastjson.JSONObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.shreport.service.ShReportMaterialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 山洪报告流域资料：上传、列表、改标签、删除、重解析、登录态预览。
 */
@Slf4j
@Tag(name = "山洪报告-流域资料")
@RestController
@RequestMapping("/sh/report/material")
public class ShReportMaterialController {

    @Autowired
    private ShReportMaterialService materialService;

    @Operation(summary = "上传流域资料")
    @PostMapping("/upload")
    public Result<?> upload(@RequestParam("file") MultipartFile file,
                            @RequestParam(required = false) String conversationId,
                            @RequestParam(required = false) String lybm,
                            @RequestParam(required = false) String lymc,
                            @RequestParam(required = false) String batchNo,
                            @RequestParam(required = false) String category,
                            @RequestParam(required = false) String chapterNos) {
        try {
            return Result.OK(materialService.upload(file, conversationId, lybm, lymc, batchNo, category, chapterNos));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("资料上传失败", e);
            return Result.error("上传失败：" + e.getMessage());
        }
    }

    @Operation(summary = "资料列表（不含解析全文）")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String conversationId,
                                                  @RequestParam(required = false) String lybm,
                                                  @RequestParam(required = false) String batchNo) {
        try {
            return Result.OK(materialService.list(conversationId, lybm, batchNo));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("资料列表失败", e);
            return Result.error("查询失败：" + e.getMessage());
        }
    }

    @Operation(summary = "修改类别/章节标签/以此为准")
    @PostMapping("/update")
    public Result<?> update(@RequestBody JSONObject body) {
        try {
            Long id = body.getLong("id");
            return Result.OK(materialService.updateTags(id, body.getString("conversationId"),
                    body.getString("category"), body.getString("chapterNos"),
                    body.getBoolean("dataOverride")));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("资料更新失败", e);
            return Result.error("更新失败：" + e.getMessage());
        }
    }

    @Operation(summary = "逻辑删除")
    @PostMapping("/delete")
    public Result<String> delete(@RequestBody JSONObject body) {
        try {
            materialService.delete(body.getLong("id"), body.getString("conversationId"));
            return Result.OK("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("资料删除失败", e);
            return Result.error("删除失败：" + e.getMessage());
        }
    }

    @Operation(summary = "重新解析")
    @PostMapping("/reparse")
    public Result<?> reparse(@RequestBody JSONObject body) {
        try {
            return Result.OK(materialService.reparse(body.getLong("id"), body.getString("conversationId")));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("资料重解析失败", e);
            return Result.error("重解析失败：" + e.getMessage());
        }
    }

    @Operation(summary = "登录态预览/下载")
    @GetMapping("/preview")
    public ResponseEntity<ByteArrayResource> preview(@RequestParam Long id,
                                                     @RequestParam(required = false) String conversationId) {
        try {
            String[] name = new String[1];
            String[] media = new String[1];
            byte[] bytes = materialService.previewBytes(id, conversationId, name, media);
            String filename = name[0] == null ? "file" : name[0];
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.parseMediaType(media[0] == null
                            ? MediaType.APPLICATION_OCTET_STREAM_VALUE : media[0]))
                    .contentLength(bytes.length)
                    .body(new ByteArrayResource(bytes));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("资料预览失败 id={}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
