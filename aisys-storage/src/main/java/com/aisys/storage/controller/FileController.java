package com.aisys.storage.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.storage.dto.FileDtos;
import com.aisys.storage.service.FileService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件操作（DDD 5.8.1）。
 * <p>所有路径服务端强制拼当前租户前缀；平台超管可绕过。已认证用户即可访问（细粒度由调用方业务层负责）。
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/browse")
    public ApiResponse<FileDtos.BrowseResult> browse(
            @RequestParam(required = false) Long poolId,
            @RequestParam(required = false) String prefix,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(fileService.browse(poolId, prefix, limit));
    }

    @PostMapping("/upload-url")
    public ApiResponse<FileDtos.UploadUrlResponse> uploadUrl(
            @RequestParam(required = false) Long poolId,
            @Valid @RequestBody FileDtos.UploadUrlRequest req) {
        return ApiResponse.success(fileService.uploadUrl(poolId, req));
    }

    /** 浏览器代理上传（multipart）：按相对 path 存储对象，返回租户相对 key（poolId 为空用默认池）。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileDtos.UploadResult> upload(
            @RequestParam(required = false) Long poolId,
            @RequestParam(required = false) String path,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(fileService.storeUpload(poolId, path,
                file.getOriginalFilename(), file.getInputStream(), file.getSize(), file.getContentType()));
    }

    @GetMapping("/download-url")
    public ApiResponse<FileDtos.DownloadUrlResponse> downloadUrl(
            @RequestParam(required = false) Long poolId,
            @RequestParam String path) {
        return ApiResponse.success(fileService.downloadUrl(poolId, path));
    }

    /** 浏览器代理下载：流式回传对象（Content-Disposition 触发浏览器下载，规避预签名内网 host 不可达）。 */
    @GetMapping("/download")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> download(
            @RequestParam(required = false) Long poolId,
            @RequestParam String path) {
        java.io.InputStream in = fileService.downloadStream(poolId, path);
        String rawName = path == null ? "file" : (path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path);
        String safeName = sanitizeFilename(rawName);
        // RFC 5987：filename 给 ASCII 兜底，filename* 给 UTF-8 编码，避免响应头注入（CR/LF）与中文乱码
        String ascii = safeName.replaceAll("[^\\x20-\\x7E]", "_");
        String encoded = java.net.URLEncoder.encode(safeName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = out -> {
            try (java.io.InputStream src = in) {
                src.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .header("Content-Disposition", disposition)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    /** 去除文件名中的控制字符 / 引号 / 反斜杠 / 换行，防响应头拆分注入。 */
    private static String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) return "file";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && sb.length() < 200; i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '"' || c == '\\') continue;
            sb.append(c);
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? "file" : s;
    }

    @DeleteMapping
    public ApiResponse<Void> delete(
            @RequestParam(required = false) Long poolId,
            @Valid @RequestBody(required = false) FileDtos.DeleteRequest req) {
        FileDtos.DeleteRequest body = req == null ? new FileDtos.DeleteRequest(null) : req;
        fileService.delete(poolId, body);
        return ApiResponse.success();
    }
}
