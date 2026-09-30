package com.aisys.storage.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.storage.dto.UploadTaskDtos;
import com.aisys.storage.service.UploadTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 上传任务接口（大文件分片：浏览器→后端→存储池；DDD 5.8）。
 * <ul>
 *   <li>POST /api/v1/files/upload-tasks          发起任务（createMultipart + 建任务）</li>
 *   <li>POST /api/v1/files/upload-tasks/{id}/chunks/{part}  上传一片（后端写 S3 part）</li>
 *   <li>POST /api/v1/files/upload-tasks/{id}/complete       完成（completeMultipart 组装入池）</li>
 *   <li>DELETE /api/v1/files/upload-tasks/{id}     取消（中止 S3 multipart，释放未完成分片）</li>
 *   <li>GET  /api/v1/files/upload-tasks            任务列表（状态查询）</li>
 *   <li>GET  /api/v1/files/upload-tasks/{id}       任务详情</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/files/upload-tasks")
public class UploadTaskController {

    private final UploadTaskService taskService;

    public UploadTaskController(UploadTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ApiResponse<UploadTaskDtos.InitiateResponse> initiate(@Valid @RequestBody UploadTaskDtos.Initiate req) {
        return ApiResponse.success(taskService.initiate(req));
    }

    @PostMapping("/{taskId}/chunks/{partNumber}")
    public ApiResponse<UploadTaskDtos.ChunkResponse> uploadChunk(
            @PathVariable Long taskId,
            @PathVariable int partNumber,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(
            taskService.uploadChunk(taskId, partNumber, file.getInputStream(), file.getSize()));
    }

    @PostMapping("/{taskId}/complete")
    public ApiResponse<UploadTaskDtos.Response> complete(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.complete(taskId));
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<UploadTaskDtos.Response> cancel(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.cancel(taskId));
    }

    @GetMapping
    public ApiResponse<PageResult<UploadTaskDtos.Response>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(taskService.list(status, page, size));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<UploadTaskDtos.Response> get(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.get(taskId));
    }
}
