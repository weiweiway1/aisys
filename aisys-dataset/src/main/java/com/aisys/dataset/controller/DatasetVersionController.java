package com.aisys.dataset.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.dataset.dto.DatasetVersionDtos;
import com.aisys.dataset.service.DatasetPreviewService;
import com.aisys.dataset.service.DatasetVersionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据集版本管理 / 预览 / 统计（DDD 5.4.1）。
 */
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}/versions")
public class DatasetVersionController {

    private final DatasetVersionService versionService;

    public DatasetVersionController(DatasetVersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping
    public ApiResponse<List<DatasetVersionDtos.Response>> list(@PathVariable Long datasetId) {
        return ApiResponse.success(versionService.list(datasetId));
    }

    @PostMapping
    @AuditLog(action = "CREATE", resource = "DATASET_VERSION",
            description = "创建数据集版本", resourceId = "#result.data.id")
    public ApiResponse<DatasetVersionDtos.CreateResponse> create(@PathVariable Long datasetId,
                                                                 @Valid @RequestBody DatasetVersionDtos.Create req) {
        return ApiResponse.success(versionService.create(datasetId, req));
    }

    @GetMapping("/{versionId}/preview")
    public ApiResponse<DatasetVersionDtos.Preview> preview(
            @PathVariable Long datasetId,
            @PathVariable Long versionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(versionService.preview(datasetId, versionId, page, size));
    }

    @GetMapping("/{versionId}/statistics")
    public ApiResponse<DatasetVersionDtos.Statistics> statistics(@PathVariable Long datasetId,
                                                                 @PathVariable Long versionId) {
        return ApiResponse.success(versionService.statistics(datasetId, versionId));
    }

    /**
     * 单样本图片字节（预览 row.url 指向）。从归档内按需抽取第 index 张图。
     * 前端用鉴权 XHR 以 blob 加载（无 token-in-query、无网关改造）。
     */
    @GetMapping("/{versionId}/samples/{index}/image")
    public ResponseEntity<byte[]> sampleImage(@PathVariable Long datasetId,
                                              @PathVariable Long versionId,
                                              @PathVariable int index) {
        DatasetPreviewService.ImageData img = versionService.sampleImage(datasetId, versionId, index);
        if (img == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(org.springframework.http.CacheControl.maxAge(600, java.util.concurrent.TimeUnit.SECONDS).cachePublic())
                .body(img.bytes());
    }

    /** 单个版本详情（原 GET /{versionId}）。 */
    @GetMapping("/{versionId}")
    public ApiResponse<DatasetVersionDtos.Response> get(@PathVariable Long datasetId,
                                                        @PathVariable Long versionId) {
        return ApiResponse.success(versionService.get(datasetId, versionId));
    }

    /** 完成版本上传：回写行数/列信息/大小并置为 ready（create→直传→complete）。 */
    @PostMapping("/{versionId}/complete")
    @AuditLog(action = "UPDATE", resource = "DATASET_VERSION",
            description = "完成版本上传", resourceId = "#versionId")
    public ApiResponse<DatasetVersionDtos.Response> complete(@PathVariable Long datasetId,
                                                             @PathVariable Long versionId) {
        return ApiResponse.success(versionService.complete(datasetId, versionId));
    }

    /** 导出/下载：返回版本对象的预签名下载 URL。 */
    @GetMapping("/{versionId}/export")
    public ApiResponse<DatasetVersionDtos.ExportResponse> export(@PathVariable Long datasetId,
                                                                 @PathVariable Long versionId) {
        return ApiResponse.success(versionService.exportUrl(datasetId, versionId));
    }

    /** 上传版本（multipart，经后端代理直传存储；落库+存储+统计+置 ready 一步完成）。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditLog(action = "CREATE", resource = "DATASET_VERSION",
            description = "上传数据集版本", resourceId = "#result.data.id")
    public ApiResponse<DatasetVersionDtos.Response> upload(
            @PathVariable Long datasetId,
            @RequestParam("version") String version,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        return ApiResponse.success(versionService.uploadAndComplete(
                datasetId, version, description, file.getInputStream(), file.getSize()));
    }

    /** 下载/格式转换下载（流式，Content-Disposition 触发浏览器下载）。targetFormat=csv 将 jsonl 转 CSV。 */
    @GetMapping("/{versionId}/download")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> download(
            @PathVariable Long datasetId, @PathVariable Long versionId,
            @RequestParam(required = false) String targetFormat) {
        DatasetVersionDtos.Response v = versionService.get(datasetId, versionId);
        // 在事务内解析实际 S3 key（流式 body 在事务外执行，不能再做 DB 查询）
        String storageKey = versionService.resolveStorageKey(datasetId, versionId);
        // 下载扩展名随目标转换格式（coco→json / yolo→txt / voc→xml），否则用存储路径扩展名
        String tf = targetFormat == null ? "" : targetFormat.trim().toLowerCase();
        String ext = switch (tf) {
            case "csv" -> "csv";
            case "coco" -> "json";
            case "yolo" -> "txt";
            case "voc" -> "xml";
            default -> (v.storagePath() != null && v.storagePath().contains(".")
                    ? v.storagePath().substring(v.storagePath().lastIndexOf('.') + 1) : "jsonl");
        };
        // 文件名仅用受控片段（datasetId 数字 + ext），version 用户可控不直接拼，防响应头注入
        String filename = "dataset-" + datasetId + "-v" + versionId + "." + ext;
        String ascii = filename.replaceAll("[^\\x20-\\x7E]", "_");
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = out -> {
            try {
                versionService.writeConvertedStream(storageKey, targetFormat, out);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        };
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + ascii + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    @DeleteMapping("/{versionId}")
    @AuditLog(action = "DELETE", resource = "DATASET_VERSION",
            description = "删除数据集版本", resourceId = "#versionId")
    public ApiResponse<Void> delete(@PathVariable Long datasetId,
                                    @PathVariable Long versionId) {
        versionService.delete(datasetId, versionId);
        return ApiResponse.success();
    }
}
