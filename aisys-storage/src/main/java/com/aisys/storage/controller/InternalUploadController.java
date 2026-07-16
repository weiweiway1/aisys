package com.aisys.storage.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.storage.dto.FileDtos;
import com.aisys.storage.service.FileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 内部上传协议端点（DDD 4.2.1）。供 model/dataset 等服务调用，
 * 实现秒传（dedup）、单次直传、分片上传（断点续传）。
 */
@RestController
@RequestMapping("/api/v1/storage/upload")
public class InternalUploadController {

    private final FileService fileService;

    public InternalUploadController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/initiate")
    public ApiResponse<FileDtos.InitiateResponse> initiate(@Valid @RequestBody FileDtos.InitiateRequest req) {
        return ApiResponse.success(fileService.initiate(req));
    }

    @PostMapping("/complete")
    public ApiResponse<FileDtos.CompleteResponse> complete(@Valid @RequestBody FileDtos.CompleteRequest req) {
        return ApiResponse.success(fileService.complete(req));
    }
}
