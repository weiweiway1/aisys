package com.aisys.common.s3.service;

import com.aisys.common.s3.config.S3Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * S3 存储服务封装（DDD 4.2）：分片上传、预签名、对象存在性、删除等基础原语。
 * 业务侧（Storage/Model/Dataset/Agent）在此之上编排秒传/断点续传/租户路径隔离等流程。
 */
@Service
public class S3StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties props;

    @Autowired
    public S3StorageService(S3Client s3, S3Presigner presigner, S3Properties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
    }

    public String bucket() {
        return props.getBucket();
    }

    public Duration presignDuration() {
        return Duration.ofMinutes(Math.max(1, props.getPresignMinutes()));
    }

    /** 确保默认 bucket 存在（启动时可调用）。 */
    public void ensureBucket(String bucket) {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (S3Exception e) {
            try {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("创建 bucket: {}", bucket);
            } catch (Exception ex) {
                log.warn("创建 bucket {} 失败（可能已存在）: {}", bucket, ex.getMessage());
            }
        }
    }

    /** 实时统计 bucket 内对象总字节与数量（分页列举求和，用于实时用量展示）。 */
    public BucketUsage statBucket(String bucket) {
        long total = 0;
        long count = 0;
        String token = null;
        do {
            ListObjectsV2Request.Builder b = ListObjectsV2Request.builder()
                    .bucket(bucket).maxKeys(1000);
            if (token != null) b.continuationToken(token);
            ListObjectsV2Response resp = s3.listObjectsV2(b.build());
            for (S3Object o : resp.contents()) {
                total += (o.size() == null ? 0 : o.size());
                count++;
            }
            token = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (token != null);
        return new BucketUsage(total, count);
    }

    /** bucket 实时用量。 */
    public record BucketUsage(long totalBytes, long objectCount) {}

    public boolean objectExists(String bucket, String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception e) {
            // 仅 404 表示对象不存在；403/5xx/网络错误上抛，避免把“权限/故障”误判为“不存在”
            // （否则秒传会误判未命中、downloadUrl 会误报 FILE_NOT_FOUND）。
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    public long objectSize(String bucket, String key) {
        return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
                .contentLength();
    }

    public void putBytes(String bucket, String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType(contentType == null ? "application/octet-stream" : contentType).build(),
                RequestBody.fromBytes(bytes));
    }

    /** 流式上传（避免把大文件整体读入内存）。 */
    public void putStream(String bucket, String key, InputStream in, long contentLength, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType(contentType == null ? "application/octet-stream" : contentType).build(),
                RequestBody.fromInputStream(in, contentLength));
    }

    public InputStream getStream(String bucket, String key) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    public String presignUpload(String bucket, String key) {
        PresignedPutObjectRequest pre = presigner.presignPutObject(b -> b
                .signatureDuration(presignDuration())
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build()));
        return pre.url().toString();
    }

    public String presignDownload(String bucket, String key) {
        PresignedGetObjectRequest pre = presigner.presignGetObject(b -> b
                .signatureDuration(presignDuration())
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()));
        return pre.url().toString();
    }

    public String presignDownload(String bucket, String key, Duration ttl) {
        PresignedGetObjectRequest pre = presigner.presignGetObject(b -> b
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build()));
        return pre.url().toString();
    }

    public String createMultipart(String bucket, String key) {
        return s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build()).uploadId();
    }

    public String presignUploadPart(String bucket, String key, String uploadId, int partNumber) {
        PresignedUploadPartRequest pre = presigner.presignUploadPart(b -> b
                .signatureDuration(presignDuration())
                .uploadPartRequest(UploadPartRequest.builder()
                        .bucket(bucket).key(key).uploadId(uploadId)
                        .partNumber(partNumber).build()));
        return pre.url().toString();
    }

    /** 后端流式直写一片（浏览器→后端→池；流式避免整片入内存，支持并发上传）。返回 eTag。 */
    public String uploadPart(String bucket, String key, String uploadId, int partNumber,
                             InputStream in, long contentLength) {
        return s3.uploadPart(UploadPartRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber)
                .contentLength(contentLength).build(),
                RequestBody.fromInputStream(in, contentLength)).eTag();
    }

    public void completeMultipart(String bucket, String key, String uploadId, List<CompletedPart> parts) {
        s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(m -> m.parts(parts)).build());
    }

    public void abortMultipart(String bucket, String key, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).build());
        } catch (Exception e) {
            log.warn("abort multipart 失败 {}: {}", key, e.getMessage());
        }
    }

    public void deleteObject(String bucket, String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    public void deleteObjects(String bucket, List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        List<ObjectIdentifier> ids = keys.stream().map(k -> ObjectIdentifier.builder().key(k).build()).toList();
        s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket)
                .delete(Delete.builder().objects(ids).build()).build());
    }
}
