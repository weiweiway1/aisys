package com.aisys.storage.service;

import com.aisys.storage.dto.FileDtos;

/** 文件操作服务（DDD 5.8.1 / 4.2.1）。 */
public interface FileService {

    FileDtos.BrowseResult browse(Long poolId, String prefix, int limit);

    FileDtos.UploadUrlResponse uploadUrl(Long poolId, FileDtos.UploadUrlRequest req);

    FileDtos.DownloadUrlResponse downloadUrl(Long poolId, String path);

    void delete(Long poolId, FileDtos.DeleteRequest req);

    /** 内部上传协议 - 发起（秒传 / 直传 / 分片）。 */
    FileDtos.InitiateResponse initiate(FileDtos.InitiateRequest req);

    /** 内部上传协议 - 完成（分片汇总校验）。 */
    FileDtos.CompleteResponse complete(FileDtos.CompleteRequest req);

    /** 直接上传单文件（multipart，头像等小文件）→ 返回下载 URL。poolId 为空用默认池。 */
    FileDtos.DownloadUrlResponse directUpload(Long poolId, String filename, byte[] bytes, String contentType);

    /** 浏览器代理上传：按相对 path 流式存储对象（path 为空则落到 uploads/），返回租户相对 key。 */
    FileDtos.UploadResult storeUpload(Long poolId, String path, String filename,
                                      java.io.InputStream in, long size, String contentType);

    /** 浏览器代理下载：返回对象输入流（相对 path，service 解析租户前缀）。 */
    java.io.InputStream downloadStream(Long poolId, String path);
}
