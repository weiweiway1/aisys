package com.aisys.dataset.service;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.exception.CommonErrorCode;
import com.aisys.common.s3.service.S3StorageService;
import com.aisys.dataset.constant.DatasetErrorCode;
import com.aisys.dataset.dto.DatasetVersionDtos;
import com.aisys.dataset.entity.Dataset;
import com.aisys.dataset.entity.DatasetVersion;
import com.aisys.dataset.mapper.DatasetMapper;
import com.aisys.dataset.mapper.DatasetVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据集版本管理（DDD 5.4）。
 * <p>preview：从 S3 getStream 读前 size 行，jsonl 逐行解析 JSON。
 * statistics：返回 row_count + column_info。
 * 上传/下载均直接走 common-s3（S3StorageService），key 约定 {tenantId}/{relPath}（见 fullKey）。
 */
@Service
public class DatasetVersionService {

    private static final Logger log = LoggerFactory.getLogger(DatasetVersionService.class);

    private final DatasetMapper datasetMapper;
    private final DatasetVersionMapper versionMapper;
    private final S3StorageService s3;
    private final ObjectMapper objectMapper;
    private final DatasetPreviewService previewService;

    public DatasetVersionService(DatasetMapper datasetMapper,
                                 DatasetVersionMapper versionMapper,
                                 S3StorageService s3,
                                 ObjectMapper objectMapper,
                                 DatasetPreviewService previewService) {
        this.datasetMapper = datasetMapper;
        this.versionMapper = versionMapper;
        this.s3 = s3;
        this.objectMapper = objectMapper;
        this.previewService = previewService;
    }

    @Transactional(readOnly = true)
    public List<DatasetVersionDtos.Response> list(Long datasetId) {
        ensureDataset(datasetId);
        return versionMapper.listByDatasetId(datasetId).stream().map(this::toResponse).toList();
    }

    /**
     * 创建版本：生成租户隔离的存储路径，落库 creating 记录，并直接经 common-s3 预签名上传 URL。
     * <p>预签名 key 与 complete/preview 读对象使用的 fullKey（{tenantId}/{relPath}）一致，
     * 保证「上传到哪个 key」与「从哪个 key 读」不会错位。
     */
    @Transactional
    public DatasetVersionDtos.CreateResponse create(Long datasetId, DatasetVersionDtos.Create req) {
        Dataset dataset = ensureDataset(datasetId);

        if (versionMapper.selectByDatasetAndVersion(datasetId, req.version()) != null) {
            throw new BusinessException(DatasetErrorCode.VERSION_EXISTS);
        }

        // 租户隔离存储路径：datasets/<tenantId>/<datasetId>/<version>.<ext>
        String storagePath = buildStoragePath(dataset, req.version());

        DatasetVersion v = new DatasetVersion();
        v.setDatasetId(datasetId);
        v.setTenantId(dataset.getTenantId());
        v.setVersion(req.version());
        v.setStoragePath(storagePath);
        v.setFileSize(0L);
        v.setRowCount(0L);
        v.setStatus("creating");
        v.setCreatedBy(UserContext.getUserId());
        try {
            versionMapper.insert(v);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 并发同版本号：唯一约束兜底，转业务错误
            throw new BusinessException(DatasetErrorCode.VERSION_EXISTS);
        }

        String uploadUrl = resolveUploadUrl(dataset.getTenantId(), storagePath);

        return new DatasetVersionDtos.CreateResponse(
                v.getId(), datasetId, req.version(), storagePath, uploadUrl, v.getStatus());
    }

    /**
     * 预览（格式感知）：按数据集实际格式（zip/yolo/coco/csv/jsonl/...）抽取样本并分页，
     * 返回 {columns, rows, total}。委托 {@link DatasetPreviewService}，不在事务内持有 S3 流。
     * 参考 refs/aisys 预览语义：目标检测给出 [id,file,width,height,annotations,split] 等结构化列。
     */
    public DatasetVersionDtos.Preview preview(Long datasetId, Long versionId, int page, int size) {
        DatasetVersion v = ensureVersion(datasetId, versionId);
        if (!"ready".equals(v.getStatus())) {
            throw new BusinessException(DatasetErrorCode.VERSION_NOT_READY);
        }
        Dataset ds = ensureDataset(datasetId);
        String key = fullKey(v.getTenantId(), v.getStoragePath());
        return previewService.preview(key, ds.getFormat(), datasetId, versionId, page, size);
    }

    /** 单样本图片字节（预览的 row.url 指向；前端鉴权 blob 加载）。 */
    public DatasetPreviewService.ImageData sampleImage(Long datasetId, Long versionId, int index) {
        DatasetVersion v = ensureVersion(datasetId, versionId);
        Dataset ds = ensureDataset(datasetId);
        String key = fullKey(v.getTenantId(), v.getStoragePath());
        return previewService.sampleImage(key, ds.getFormat(), index);
    }

    /**
     * 统计（多维，参考 refs/aisys）：委托 {@link DatasetPreviewService}，不在事务内持有 S3 流。
     * 落库的 rowCount/fileSize/columnInfo/checksum 作为兜底与表格类格式的数据源。
     */
    public DatasetVersionDtos.Statistics statistics(Long datasetId, Long versionId) {
        DatasetVersion v = ensureVersion(datasetId, versionId);
        if (!"ready".equals(v.getStatus())) {
            throw new BusinessException(DatasetErrorCode.VERSION_NOT_READY);
        }
        Dataset ds = ensureDataset(datasetId);
        String key = fullKey(v.getTenantId(), v.getStoragePath());
        Object columnInfo = parseColumnInfo(v.getColumnInfo());
        return previewService.statistics(key, ds.getFormat(), v.getFileSize(), v.getRowCount(), columnInfo, v.getChecksum());
    }

    @Transactional
    public void delete(Long datasetId, Long versionId) {
        DatasetVersion v = ensureVersion(datasetId, versionId);
        String key = fullKey(v.getTenantId(), v.getStoragePath());
        // 删除对象（尽力，失败不阻断记录删除）
        try {
            s3.deleteObject(s3.bucket(), key);
        } catch (Exception e) {
            log.warn("删除版本对象失败 path={}：{}", key, e.getMessage());
        }
        versionMapper.deleteById(versionId);
    }

    /** 单个版本详情。 */
    @Transactional(readOnly = true)
    public DatasetVersionDtos.Response get(Long datasetId, Long versionId) {
        return toResponse(ensureVersion(datasetId, versionId));
    }

    /** 按版本 ID 查询（跨服务解析 storagePath 用，不校验 datasetId 归属——调度侧用）。 */
    @Transactional(readOnly = true)
    public DatasetVersionDtos.Response getById(Long versionId) {
        DatasetVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new BusinessException(DatasetErrorCode.VERSION_NOT_FOUND);
        }
        return toResponse(v);
    }

    /**
     * 完成版本上传：读取已上传对象，统计行数/列信息/大小，置为 ready。
     * 前端 create → 直传 S3 → 调用 complete 回写统计。
     */
    @Transactional
    public DatasetVersionDtos.Response complete(Long datasetId, Long versionId) {
        DatasetVersion v = ensureVersion(datasetId, versionId);
        String key = fullKey(v.getTenantId(), v.getStoragePath());
        long size = s3.objectSize(s3.bucket(), key);
        long rows;
        List<String> columns;
        try (InputStream in = s3.getStream(s3.bucket(), key)) {
            VersionStats vs = computeStats(in);
            rows = vs.rows();
            columns = vs.columns();
        } catch (Exception e) {
            log.error("complete 读取版本对象失败 dataset={} version={} path={}", datasetId, versionId, key, e);
            throw new BusinessException(DatasetErrorCode.PREVIEW_FAILED);
        }
        versionMapper.updateStatus(versionId, "ready", size, null, rows, buildColumnInfoJson(columns));
        return toResponse(versionMapper.selectById(versionId));
    }

    /** 统计输入流的行数与列名（jsonl：首行收集列名）。 */
    private VersionStats computeStats(InputStream in) throws IOException {
        long rows = 0;
        List<String> columns = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rows++;
                if (columns.isEmpty()) parseJsonl(line, columns);
            }
        }
        return new VersionStats(rows, columns);
    }

    private record VersionStats(long rows, List<String> columns) {}

    /**
     * 上传版本内容并完成：落库→存储对象→统计→置 ready。
     * 浏览器经后端代理直传（避免预签名 URL 内网主机不可达）。
     */
    @Transactional
    public DatasetVersionDtos.Response uploadAndComplete(Long datasetId, String version, String description,
                                                         InputStream fileIn, long fileSize) {
        Dataset dataset = ensureDataset(datasetId);
        if (version == null || version.isBlank()) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "version 不能为空");
        }
        if (versionMapper.selectByDatasetAndVersion(datasetId, version) != null) {
            throw new BusinessException(DatasetErrorCode.VERSION_EXISTS);
        }
        String storagePath = buildStoragePath(dataset, version);
        String key = fullKey(dataset.getTenantId(), storagePath);
        DatasetVersion v = new DatasetVersion();
        v.setDatasetId(datasetId);
        v.setTenantId(dataset.getTenantId());
        v.setVersion(version);
        v.setStoragePath(storagePath);
        v.setFileSize(fileSize);
        v.setRowCount(0L);
        v.setStatus("creating");
        v.setCreatedBy(UserContext.getUserId());
        try {
            versionMapper.insert(v);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 并发同版本号：唯一约束兜底，转业务错误
            throw new BusinessException(DatasetErrorCode.VERSION_EXISTS);
        }

        try {
            // 流式上传（避免把整个文件读入堆）；fileSize 已知，用 putStream 而非 putBytes
            s3.putStream(s3.bucket(), key, fileIn, fileSize, "application/json");
            long size = s3.objectSize(s3.bucket(), key);
            long rows;
            List<String> columns;
            try (InputStream in = s3.getStream(s3.bucket(), key)) {
                VersionStats vs = computeStats(in);
                rows = vs.rows();
                columns = vs.columns();
            }
            versionMapper.updateStatus(v.getId(), "ready", size, null, rows, buildColumnInfoJson(columns));
        } catch (Exception e) {
            log.error("uploadAndComplete 存储失败 path={}", key, e);
            // 对象已写入但后续统计失败 → 事务回滚会删 DB 行，需尽力删 S3 对象避免孤儿泄漏
            try {
                s3.deleteObject(s3.bucket(), key);
            } catch (Exception cleanupEx) {
                log.warn("uploadAndComplete 回滚清理 S3 对象失败 path={}: {}", key, cleanupEx.getMessage());
            }
            throw new BusinessException(DatasetErrorCode.PREVIEW_FAILED);
        }
        return toResponse(versionMapper.selectById(v.getId()));
    }

    /** 下载版本对象流（由 Controller 包装为流式响应）。非事务：流在事务外消费。 */
    public InputStream downloadStream(Long datasetId, Long versionId) {
        DatasetVersion v = ensureVersion(datasetId, versionId);
        return s3.getStream(s3.bucket(), fullKey(v.getTenantId(), v.getStoragePath()));
    }

    /** 在事务内解析版本的实际 S3 key（{tenantId}/{relPath}），供流式下载在事务外使用。 */
    @Transactional(readOnly = true)
    public String resolveStorageKey(Long datasetId, Long versionId) {
        DatasetVersion v = ensureVersion(datasetId, versionId);
        return fullKey(v.getTenantId(), v.getStoragePath());
    }

    /** 格式转换下载：支持 csv/coco/yolo/voc，其他/null 原始流式。
     * 注意：key 必须由调用方在事务内经 resolveStorageKey 解析好传入 —— 本方法在 StreamingResponseBody
     * 的流式线程里执行，已脱离事务/RLS 上下文，不能再做 DB 查询。 */
    public void writeConvertedStream(String key, String targetFormat,
                                     java.io.OutputStream out) throws java.io.IOException {
        if (targetFormat == null || targetFormat.isBlank() || "raw".equalsIgnoreCase(targetFormat)) {
            try (InputStream in = s3.getStream(s3.bucket(), key)) {
                in.transferTo(out);
            }
            return;
        }
        try (InputStream in = s3.getStream(s3.bucket(), key)) {
            com.aisys.dataset.format.FormatConverters.convert(in, out, targetFormat);
        }
    }

    /** jsonl → CSV 转换（第一行收集列名作为表头，后续行按列输出）。 */
    private void convertJsonlToCsv(InputStream in, java.io.OutputStream out) throws java.io.IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        List<String> headers = new ArrayList<>();
        boolean headerWritten = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            Map<String, Object> row;
            try {
                row = JsonLineReader.readObject(line);
            } catch (Exception e) {
                continue;
            }
            if (row == null) continue;
            if (!headerWritten) {
                headers.addAll(row.keySet());
                writer.write(String.join(",", headers));
                writer.newLine();
                headerWritten = true;
            }
            List<String> vals = new ArrayList<>();
            for (String h : headers) {
                Object val = row.get(h);
                String s = val == null ? "" : String.valueOf(val).replace("\"", "\"\"");
                if (s.contains(",") || s.contains("\"") || s.contains("\n")) s = "\"" + s + "\"";
                vals.add(s);
            }
            writer.write(String.join(",", vals));
            writer.newLine();
        }
        writer.flush();
    }

    /** 导出/下载：返回版本对象的预签名下载 URL（原始文件）。 */
    @Transactional(readOnly = true)
    public DatasetVersionDtos.ExportResponse exportUrl(Long datasetId, Long versionId) {
        DatasetVersion v = ensureVersion(datasetId, versionId);
        String url = s3.presignDownload(s3.bucket(), fullKey(v.getTenantId(), v.getStoragePath()));
        long expires = s3.presignDuration().getSeconds();
        return new DatasetVersionDtos.ExportResponse(v.getId(), v.getVersion(), url, expires);
    }

    /** 构造列信息 JSON：[{"name":"col","type":"string"},...]。 */
    private String buildColumnInfoJson(List<String> columns) {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (String c : columns) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c);
                m.put("type", "string");
                list.add(m);
            }
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- helpers ----------

    private Dataset ensureDataset(Long datasetId) {
        Dataset d = datasetMapper.selectById(datasetId);
        if (d == null || "deleted".equals(d.getStatus())) {
            throw new BusinessException(DatasetErrorCode.DATASET_NOT_FOUND);
        }
        return d;
    }

    private DatasetVersion ensureVersion(Long datasetId, Long versionId) {
        DatasetVersion v = versionMapper.selectById(versionId);
        if (v == null || !Objects.equals(v.getDatasetId(), datasetId)) {
            throw new BusinessException(DatasetErrorCode.VERSION_NOT_FOUND);
        }
        return v;
    }

    private String buildStoragePath(Dataset dataset, String version) {
        // relPath（不含租户段）：租户前缀由 fullKey 在 S3 访问时统一拼接，
        // 与存储模块 UploadTask 的 resolveTenantKey 约定一致 —— 这样大文件分片上传
        // （create→initiateUploadTask(relPath)→complete→completeDatasetVersion）
        // 与小文件直传写入的是同一个最终 key，complete 才能读到对象。
        String ext = dataset.getFormat() == null ? "jsonl" : dataset.getFormat();
        return "datasets/" + dataset.getId() + "/" + version + "." + ext;
    }

    /** 拼接实际 S3 key：{tenantId}/{relPath}。tenantId 优先取版本自身（跨租户超管也能正确定位）。 */
    private String fullKey(Long tenantId, String relPath) {
        if (relPath == null) return null;
        if (tenantId == null) return relPath;
        return tenantId + "/" + relPath;
    }

    /**
     * 生成版本对象的上传预签名 URL。
     * <p>直接经 common-s3 预签名 {tenantId}/{relPath}（与 complete/statistics/preview 读对象所用 fullKey 一致），
     * 不再跨服务调用 storage（旧 Feign getUploadUrl 走 GET 而 storage 端为 POST+RequestBody，契约不匹配且会落错 key）。
     */
    private String resolveUploadUrl(Long tenantId, String relPath) {
        return s3.presignUpload(s3.bucket(), fullKey(tenantId, relPath));
    }

    /** 解析单行 JSON，并按首次出现顺序收集列名。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonl(String line, List<String> columns) {
        Map<String, Object> row = new LinkedHashMap<>();
        try {
            Map<String, Object> obj = JsonLineReader.readObject(line);
            if (obj != null) {
                for (Map.Entry<String, Object> e : obj.entrySet()) {
                    if (!columns.contains(e.getKey())) columns.add(e.getKey());
                    row.put(e.getKey(), e.getValue());
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 行：放原始文本到 value 列
            row.put("value", line);
            if (!columns.contains("value")) columns.add("value");
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private Object parseColumnInfo(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JsonLineReader.readAny(json);
        } catch (Exception e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractColumnNames(String columnInfo) {
        if (columnInfo == null || columnInfo.isBlank()) return List.of();
        List<String> names = new ArrayList<>();
        try {
            Object parsed = JsonLineReader.readAny(columnInfo);
            if (parsed instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m && m.get("name") != null) {
                        names.add(String.valueOf(m.get("name")));
                    }
                }
            }
        } catch (Exception ignored) {
            // 保留空列
        }
        return names;
    }

    private DatasetVersionDtos.Response toResponse(DatasetVersion v) {
        return new DatasetVersionDtos.Response(
                v.getId(), v.getDatasetId(), v.getVersion(), v.getStoragePath(),
                v.getFileSize(), v.getChecksum(), v.getRowCount(),
                parseColumnInfo(v.getColumnInfo()), v.getStatus(),
                v.getCreatedBy(), v.getCreatedAt(), v.getUpdatedAt(),
                v.getDatasetName(), v.getDatasetDescription(), v.getTaskType(), v.getFormat(), v.getSampleCount()
        );
    }
}
