package com.aisys.dataset.service;

import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.s3.service.S3StorageService;
import com.aisys.dataset.constant.DatasetErrorCode;
import com.aisys.dataset.dto.DatasetVersionDtos.Preview;
import com.aisys.dataset.dto.DatasetVersionDtos.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 数据集预览/统计/单图（格式感知，参考 refs/aisys）。
 * <p>按需下载原始对象 → 探测格式 → 抽取样本：
 * <ul>
 *   <li>ZIP：YOLO（data.yaml+images/labels）/ COCO（instances.json）/ 图片目录。</li>
 *   <li>CSV / JSONL / JSON 数组 / 纯文本。</li>
 * </ul>
 * 预览对检测/分类给每样本一个单图 URL（{@code /samples/{index}/image}），前端用鉴权 blob 加载显示真实图片；
 * 统计返回 9 维（样本/文件/大小/类别/标注/划分/图像尺寸/目标尺寸）。
 */
@Service
public class DatasetPreviewService {

    private static final Logger log = LoggerFactory.getLogger(DatasetPreviewService.class);

    private static final Set<String> IMG_EXT =
            new LinkedHashSet<>(Arrays.asList("jpg", "jpeg", "png", "bmp", "webp"));
    private static final int MAX_TOTAL = 5000;

    private final S3StorageService s3;
    private final ObjectMapper objectMapper;

    public DatasetPreviewService(S3StorageService s3, ObjectMapper objectMapper) {
        this.s3 = s3;
        this.objectMapper = objectMapper;
    }

    /** 单图结果。 */
    public record ImageData(byte[] bytes, String contentType) {}

    /** 轻量样本（仅 zip 内图片的廉价元数据；尺寸/标注按需 enrich）。 */
    private record ImageSample(int index, String entry, String split, String label, long sizeBytes) {}

    // ============================ 预览 ============================

    public Preview preview(String fullKey, String datasetFormat, Long datasetId, Long versionId, int page, int size) {
        int maxSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        Path tmp = null;
        try {
            tmp = download(fullKey);
            String fmt = detectFormat(headOf(tmp), fullKey, datasetFormat);
            return switch (fmt) {
                case "zip"   -> previewZip(tmp, datasetId, versionId, safePage, maxSize);
                case "csv"   -> previewCsv(tmp, safePage, maxSize);
                case "jsonl" -> previewJsonl(tmp, safePage, maxSize);
                case "json"  -> previewJsonArray(tmp, safePage, maxSize);
                default      -> previewTextFallback(tmp, safePage, maxSize);
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("预览数据集失败 key={} fmt={}", fullKey, datasetFormat, e);
            throw new BusinessException(DatasetErrorCode.PREVIEW_FAILED);
        } finally {
            cleanup(tmp);
        }
    }

    private Preview previewZip(Path tmp, Long datasetId, Long versionId, int page, int size) throws IOException {
        try (ZipFile zf = new ZipFile(tmp.toFile())) {
            ZipEntry dataYaml = findEntry(zf, "data.yaml");
            YoloMeta meta = dataYaml != null ? parseYoloMeta(readEntry(zf, dataYaml)) : new YoloMeta();
            List<ImageSample> samples = enumerateImageSamples(zf, meta);
            long total = samples.size();
            List<String> columns = Arrays.asList("id", "file", "width", "height", "annotations", "split");
            List<Map<String, Object>> rows = new ArrayList<>();
            int from = (page - 1) * size;
            for (int i = from; i < Math.min(from + size, samples.size()); i++) {
                ImageSample s = samples.get(i);
                Dimension dim = readDim(zf, s.entry());
                int ann = dataYaml != null ? countYoloAnnotations(zf, s.entry()) : cocoOrZeroAnn(zf, s, dataYaml);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", s.index() + 1);
                row.put("file", simpleName(s.entry()));
                row.put("width", dim.width);
                row.put("height", dim.height);
                row.put("annotations", ann);
                row.put("split", s.split());
                if (datasetId != null && versionId != null) {
                    row.put("url", sampleImageUrl(datasetId, versionId, s.index()));
                }
                rows.add(row);
            }
            return new Preview(columns, rows, total);
        }
    }

    private int cocoOrZeroAnn(ZipFile zf, ImageSample s, ZipEntry dataYaml) {
        // 非 YOLO（如纯图片目录）无标注
        return 0;
    }

    private String sampleImageUrl(Long datasetId, Long versionId, int index) {
        return "/api/v1/datasets/" + datasetId + "/versions/" + versionId + "/samples/" + index + "/image";
    }

    // ============================ 单图 ============================

    public ImageData sampleImage(String fullKey, String datasetFormat, int index) {
        Path tmp = null;
        try {
            tmp = download(fullKey);
            String fmt = detectFormat(headOf(tmp), fullKey, datasetFormat);
            if (!"zip".equals(fmt)) return null;
            try (ZipFile zf = new ZipFile(tmp.toFile())) {
                ZipEntry dataYaml = findEntry(zf, "data.yaml");
                YoloMeta meta = dataYaml != null ? parseYoloMeta(readEntry(zf, dataYaml)) : new YoloMeta();
                List<ImageSample> samples = enumerateImageSamples(zf, meta);
                if (index < 0 || index >= samples.size()) return null;
                ZipEntry img = zf.getEntry(samples.get(index).entry());
                if (img == null) return null;
                return new ImageData(readEntry(zf, img), contentTypeOf(img.getName()));
            }
        } catch (Exception e) {
            log.warn("抽取单图失败 key={} index={}: {}", fullKey, index, e.getMessage());
            return null;
        } finally {
            cleanup(tmp);
        }
    }

    // ============================ 统计（多维） ============================

    public Statistics statistics(String fullKey, String datasetFormat, Long fileSize, Long rowCount,
                                 Object columnInfo, String checksum) {
        Path tmp = null;
        try {
            tmp = download(fullKey);
            String fmt = detectFormat(headOf(tmp), fullKey, datasetFormat);
            return switch (fmt) {
                case "zip" -> statisticsZip(tmp, fileSize);
                case "csv" -> statisticsTabular(tmp, fileSize, rowCount, ",");
                default -> statisticsTabular(tmp, fileSize, rowCount, null);
            };
        } catch (Exception e) {
            log.warn("统计数据集失败 key={} fmt={}: {}", fullKey, datasetFormat, e.getMessage());
            // 兜底：返回基础字段，不阻断 UI
            return new Statistics(rowCount, fileSize, columnInfo, null, checksum,
                    rowCount, null, fileSize, null, null, null, null, null, null);
        } finally {
            cleanup(tmp);
        }
    }

    private Statistics statisticsZip(Path tmp, Long fileSize) throws IOException {
        try (ZipFile zf = new ZipFile(tmp.toFile())) {
            ZipEntry dataYaml = findEntry(zf, "data.yaml");
            ZipEntry cocoJson = (dataYaml == null) ? findCocoJson(zf) : null;
            if (cocoJson != null) return statisticsCoco(zf, cocoJson, fileSize);
            YoloMeta meta = dataYaml != null ? parseYoloMeta(readEntry(zf, dataYaml)) : new YoloMeta();
            List<ImageSample> samples = enumerateImageSamples(zf, meta);
            int sampleCount = samples.size();
            long totalSize = 0;
            Map<String, Long> splitDist = new LinkedHashMap<>();
            Map<String, Long> imageSizeDist = new LinkedHashMap<>();
            Map<String, Long> classDist = new LinkedHashMap<>();
            Map<String, Long> objectSizeDist = new LinkedHashMap<>();
            long totalAnn = 0;
            for (ImageSample s : samples) {
                totalSize += s.sizeBytes() > 0 ? s.sizeBytes() : 0;
                splitDist.merge(s.split(), 1L, Long::sum);
                Dimension dim = readDim(zf, s.entry());
                imageSizeDist.merge(imageSizeBucket(dim.width, dim.height), 1L, Long::sum);
                if (dataYaml != null) {
                    List<double[]> boxes = readYoloBoxes(zf, s.entry());
                    totalAnn += boxes.size();
                    for (double[] b : boxes) {
                        int cid = (int) b[0];
                        classDist.merge(meta.nameOf(cid), 1L, Long::sum);
                        objectSizeDist.merge(objectSizeBucketNorm(b[3], b[4]), 1L, Long::sum);
                    }
                } else {
                    classDist.merge(s.label(), 1L, Long::sum);
                }
            }
            if (totalSize <= 0) totalSize = fileSize == null ? 0 : fileSize;
            Double avg = sampleCount > 0 ? (double) totalAnn / sampleCount : null;
            Integer classesCount = dataYaml != null
                    ? (meta.names.isEmpty() ? (classDist.isEmpty() ? null : classDist.size()) : meta.names.size())
                    : (classDist.isEmpty() ? null : classDist.size());
            return new Statistics((long) sampleCount, totalSize, null, classDist, null,
                    (long) sampleCount, sampleCount, totalSize, classesCount, totalAnn, avg,
                    splitDist, imageSizeDist, dataYaml != null ? objectSizeDist : null);
        }
    }

    @SuppressWarnings("unchecked")
    private Statistics statisticsCoco(ZipFile zf, ZipEntry jsonEntry, Long fileSize) throws IOException {
        Map<Object, int[]> imageAnnCount = new LinkedHashMap<>(); // imageId -> [count]
        Map<Object, Dimension> imageDim = new LinkedHashMap<>();
        Map<String, Long> classDist = new LinkedHashMap<>();
        Map<String, Long> objectSizeDist = new LinkedHashMap<>();
        Map<Object, String> catName = new LinkedHashMap<>();
        int imageCount = 0;
        long totalAnn = 0;
        try {
            Map<String, Object> root = objectMapper.readValue(new ByteArrayInputStream(readEntry(zf, jsonEntry)), Map.class);
            Object cats = root.get("categories");
            if (cats instanceof List<?> list) {
                for (Object o : list) if (o instanceof Map<?, ?> m) catName.put(m.get("id"), String.valueOf(m.get("name")));
            }
            Object imgs = root.get("images");
            if (imgs instanceof List<?> list) {
                for (Object o : list) if (o instanceof Map<?, ?> m) {
                    Object id = m.get("id");
                    imageAnnCount.put(id, new int[]{0});
                    int w = asInt(m.get("width")), h = asInt(m.get("height"));
                    imageDim.put(id, new Dimension(w, h));
                    imageCount++;
                }
            }
            Object anns = root.get("annotations");
            if (anns instanceof List<?> list) {
                for (Object o : list) if (o instanceof Map<?, ?> m) {
                    Object imgId = m.get("image_id");
                    int[] c = imageAnnCount.get(imgId);
                    if (c != null) c[0]++;
                    Object catId = m.get("category_id");
                    classDist.merge(catName.getOrDefault(catId, String.valueOf(catId)), 1L, Long::sum);
                    Dimension d = imageDim.get(imgId);
                    Object bbox = m.get("bbox");
                    if (bbox instanceof List<?> bb && bb.size() >= 4 && d != null && d.width > 0 && d.height > 0) {
                        double bw = asDbl(bb.get(2)), bh = asDbl(bb.get(3));
                        objectSizeDist.merge(objectSizeBucketAbs(bw * bh, d.width, d.height), 1L, Long::sum);
                    }
                    totalAnn++;
                }
            }
        } catch (Exception e) {
            log.warn("解析 COCO 统计失败: {}", e.getMessage());
        }
        Map<String, Long> imageSizeDist = new LinkedHashMap<>();
        for (Dimension d : imageDim.values()) imageSizeDist.merge(imageSizeBucket(d.width, d.height), 1L, Long::sum);
        Double avg = imageCount > 0 ? (double) totalAnn / imageCount : null;
        Integer classesCount = catName.isEmpty() ? null : catName.size();
        long totalSize = fileSize == null ? 0 : fileSize;
        return new Statistics((long) imageCount, totalSize, null, classDist, null,
                (long) imageCount, imageCount, totalSize, classesCount, totalAnn, avg,
                null, imageSizeDist, objectSizeDist);
    }

    /** 表格类（jsonl/csv）统计：sampleCount=行数；classDistribution 取 label/class/category 字段。 */
    private Statistics statisticsTabular(Path tmp, Long fileSize, Long rowCount, String explicitCsv) throws IOException {
        long rows = 0;
        Map<String, Long> classDist = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new BufferedInputStream(Files.newInputStream(tmp)), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            if (first == null) return baseTabular(0, fileSize, rowCount, null);
            // csv：第二列起为数据；jsonl：JSON 对象
            boolean isCsv = explicitCsv != null || (!first.trim().startsWith("{") && first.contains(","));
            List<String> headers = isCsv ? parseCsvLine(first) : null;
            int labelIdx = isCsv ? labelColumnIndex(headers) : -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rows++;
                if (rows >= MAX_TOTAL) break;
                if (isCsv) {
                    List<String> vals = parseCsvLine(line);
                    if (labelIdx >= 0 && labelIdx < vals.size()) classDist.merge(vals.get(labelIdx), 1L, Long::sum);
                } else if (line.trim().startsWith("{")) {
                    try {
                        Map<String, Object> obj = objectMapper.readValue(line, Map.class);
                        for (String k : new String[]{"label", "class", "category", "target", "tag"}) {
                            if (obj.containsKey(k)) { classDist.merge(String.valueOf(obj.get(k)), 1L, Long::sum); break; }
                        }
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {}
        return baseTabular(rows, fileSize, rowCount, classDist);
    }

    private Statistics baseTabular(long rows, Long fileSize, Long rowCount, Map<String, Long> classDist) {
        long n = rowCount != null ? rowCount : rows;
        return new Statistics(n, fileSize, null, classDist, null, n, null, fileSize, null, null, null, null, null, null);
    }

    private int labelColumnIndex(List<String> headers) {
        if (headers == null) return -1;
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).toLowerCase(Locale.ROOT);
            if (h.equals("label") || h.equals("class") || h.equals("category") || h.equals("target") || h.equals("tag")) return i;
        }
        return headers.size() > 1 ? 1 : -1; // 兜底：第二列
    }

    // ============================ 格式探测 / 下载 ============================

    private Path download(String fullKey) throws IOException {
        Path tmp = Files.createTempFile("ds-preview-", ".bin");
        try (InputStream in = s3.getStream(s3.bucket(), fullKey)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            cleanup(tmp);
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
        return tmp;
    }

    private void cleanup(Path tmp) {
        if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
    }

    private byte[] headOf(Path tmp) throws IOException {
        try (InputStream in = Files.newInputStream(tmp)) {
            byte[] head = new byte[512];
            int n = in.read(head);
            return n <= 0 ? new byte[0] : Arrays.copyOf(head, n);
        }
    }

    private String detectFormat(byte[] head, String fullKey, String datasetFormat) {
        if (isZip(head)) return "zip";
        if (isGzip(head)) return "zip";
        String ext = extOf(fullKey);
        if (ext != null) {
            switch (ext) {
                case "zip", "tar", "gz", "tgz" -> { return "zip"; }
                case "csv" -> { return "csv"; }
                case "jsonl", "jsonlines" -> { return "jsonl"; }
                case "json" -> { return "json"; }
            }
        }
        if (datasetFormat != null) {
            String df = datasetFormat.toLowerCase(Locale.ROOT);
            if (df.equals("yolo") || df.equals("coco") || df.equals("voc")) return "zip";
            if (df.equals("csv")) return "csv";
            if (df.startsWith("jsonl")) return "jsonl";
        }
        String s = new String(head, StandardCharsets.UTF_8).trim();
        if (s.startsWith("{")) return "jsonl";
        if (s.startsWith("[")) return "json";
        if (s.contains(",")) return "csv";
        return "text";
    }

    private boolean isZip(byte[] h) {
        return h.length >= 4 && h[0] == 0x50 && h[1] == 0x4b && (h[2] == 0x03 || h[2] == 0x05 || h[2] == 0x07);
    }

    private boolean isGzip(byte[] h) {
        return h.length >= 2 && h[0] == 0x1f && h[1] == (byte) 0x8b;
    }

    private String extOf(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? null : lower.substring(dot + 1);
    }

    private String contentTypeOf(String name) {
        String ext = extOf(name);
        if (ext == null) return "application/octet-stream";
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }

    // ============================ ZIP 辅助 ============================

    private List<ImageSample> enumerateImageSamples(ZipFile zf, YoloMeta meta) {
        List<ImageSample> out = new ArrayList<>();
        Enumeration<? extends ZipEntry> it = zf.entries();
        while (it.hasMoreElements() && out.size() < MAX_TOTAL) {
            ZipEntry e = it.nextElement();
            if (e.isDirectory() || !IMG_EXT.contains(extOf(e.getName()))) continue;
            int idx = out.size();
            String entry = e.getName();
            out.add(new ImageSample(idx, entry, meta.splitOf(entry), inferLabel(entry), e.getSize()));
        }
        out.sort((a, b) -> a.entry().compareTo(b.entry()));
        // 重排 index 为排序后位置
        List<ImageSample> sorted = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            ImageSample s = out.get(i);
            sorted.add(new ImageSample(i, s.entry(), s.split(), s.label(), s.sizeBytes()));
        }
        return sorted;
    }

    private ZipEntry findEntry(ZipFile zf, String suffix) {
        Enumeration<? extends ZipEntry> it = zf.entries();
        ZipEntry best = null;
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(suffix)) {
                if (!n.contains("/")) return e;
                if (best == null) best = e;
            }
        }
        return best;
    }

    private ZipEntry findCocoJson(ZipFile zf) {
        Enumeration<? extends ZipEntry> it = zf.entries();
        ZipEntry candidate = null;
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".json")) continue;
            if (n.contains("instance") || n.contains("annotation") || n.endsWith("coco.json")) return e;
            if (candidate == null) candidate = e;
        }
        if (candidate != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = objectMapper.readValue(new ByteArrayInputStream(readEntry(zf, candidate)), Map.class);
                if (root.containsKey("images") && root.containsKey("annotations")) return candidate;
            } catch (Exception ignore) {}
        }
        return null;
    }

    /** YOLO 标注数。 */
    private int countYoloAnnotations(ZipFile zf, String imageEntryName) {
        return readYoloBoxes(zf, imageEntryName).size();
    }

    /** 读 YOLO label 文件的所有框 [class, cx, cy, w, h]（归一化）。 */
    private List<double[]> readYoloBoxes(ZipFile zf, String imageEntryName) {
        String label = imageEntryName.replace("\\", "/");
        label = label.replace("/images/", "/labels/");
        if (label.contains("images/")) label = label.replace("images/", "labels/");
        int dot = label.lastIndexOf('.');
        label = (dot > 0 ? label.substring(0, dot) : label) + ".txt";
        ZipEntry le = zf.getEntry(label);
        if (le == null) return List.of();
        List<double[]> boxes = new ArrayList<>();
        try {
            for (String line : new String(readEntry(zf, le), StandardCharsets.UTF_8).split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");
                if (p.length >= 5) {
                    double[] b = new double[5];
                    for (int i = 0; i < 5; i++) b[i] = Double.parseDouble(p[i]);
                    boxes.add(b);
                }
            }
        } catch (Exception ignore) {}
        return boxes;
    }

    private Dimension readDim(ZipFile zf, String entryName) {
        ZipEntry e = zf.getEntry(entryName);
        if (e == null) return new Dimension(0, 0);
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(readEntry(zf, e)))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader r = readers.next();
                r.setInput(iis);
                Dimension d = new Dimension(r.getWidth(0), r.getHeight(0));
                r.dispose();
                return d;
            }
        } catch (Exception ignore) {}
        return new Dimension(0, 0);
    }

    private byte[] readEntry(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private String simpleName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String inferLabel(String path) {
        String[] parts = path.replace("\\", "/").split("/");
        return parts.length >= 2 ? parts[parts.length - 2] : "-";
    }

    private static String inferSplit(String path) {
        String p = path.replace("\\", "/").toLowerCase(Locale.ROOT);
        if (p.contains("/train") || p.startsWith("train")) return "train";
        if (p.contains("/val") || p.startsWith("val")) return "val";
        if (p.contains("/test") || p.startsWith("test")) return "test";
        return "-";
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private static double asDbl(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    // ---------- 分桶（移植 refs） ----------

    private static String imageSizeBucket(int w, int h) {
        long px = (w > 0 && h > 0) ? (long) w * h : 0;
        if (px <= 0) return "未知";
        if (px < 320 * 240) return "< 320x240";
        if (px < 640 * 480) return "320x240 ~ 640x480";
        if (px < 1280 * 720) return "640x480 ~ 1280x720";
        if (px < 1920 * 1080) return "1280x720 ~ 1920x1080";
        if (px < 3840 * 2160) return "1920x1080 ~ 4K";
        return "> 4K";
    }

    /** YOLO 归一化 bw,bh（0-1）。 */
    private static String objectSizeBucketNorm(double bw, double bh) {
        double ratio = bw * bh;
        return objectSizeBucketRatio(ratio);
    }

    /** COCO 绝对 bbox 面积 / 图像面积。 */
    private static String objectSizeBucketAbs(double bboxArea, int imgW, int imgH) {
        double ratio = (imgW > 0 && imgH > 0) ? bboxArea / ((double) imgW * imgH) : 0;
        return objectSizeBucketRatio(ratio);
    }

    private static String objectSizeBucketRatio(double ratio) {
        if (ratio <= 0) return "未知";
        if (ratio < 0.01) return "极小 (<1%)";
        if (ratio < 0.05) return "小 (1%~5%)";
        if (ratio < 0.15) return "中 (5%~15%)";
        if (ratio < 0.40) return "大 (15%~40%)";
        return "极大 (>40%)";
    }

    // ---------- YOLO data.yaml 解析 ----------

    private YoloMeta parseYoloMeta(byte[] bytes) {
        YoloMeta m = new YoloMeta();
        try {
            String content = new String(bytes, StandardCharsets.UTF_8);
            boolean inNames = false;
            for (String raw : content.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) { if (!raw.startsWith(" ") && !raw.startsWith("\t")) inNames = false; continue; }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    // names 子项 "0: person" 可能无缩进被 trim
                    if (inNames) parseNameLine(line, m);
                    continue;
                }
                String k = line.substring(0, colon).trim();
                String v = line.substring(colon + 1).trim().replaceAll("^['\"]|['\"]$", "");
                if (k.equals("names")) { inNames = v.isEmpty(); if (!v.isEmpty()) { /* 内联字典暂不解析 */ } continue; }
                if (inNames) { parseNameLine(line, m); continue; }
                if (List.of("train", "val", "test").contains(k) && !v.isEmpty()) m.splitDirs.add(v);
            }
        } catch (Exception ignore) {}
        return m;
    }

    private void parseNameLine(String line, YoloMeta m) {
        int colon = line.indexOf(':');
        if (colon < 0) return;
        try {
            int id = Integer.parseInt(line.substring(0, colon).trim());
            String name = line.substring(colon + 1).trim().replaceAll("^['\"]|['\"]$", "");
            if (!name.isEmpty()) m.names.put(id, name);
        } catch (Exception ignore) {}
    }

    private static class YoloMeta {
        final List<String> splitDirs = new ArrayList<>();
        final Map<Integer, String> names = new LinkedHashMap<>();
        String nameOf(int cid) { String n = names.get(cid); return n != null ? n : ("class_" + cid); }
        String splitOf(String imageEntryName) {
            String p = imageEntryName.replace("\\", "/");
            for (String dir : splitDirs) {
                String d = dir.replace("\\", "/");
                String base = d.contains("/") ? d.substring(d.lastIndexOf('/') + 1) : d;
                if (p.contains("/" + d + "/") || p.contains(d + "/") || p.contains("/" + base + "/")) {
                    return d.contains("/") ? d.substring(d.lastIndexOf('/') + 1) : d;
                }
            }
            return inferSplit(p);
        }
    }

    // ============================ 文本格式预览 ============================

    private Preview previewCsv(Path tmp, int page, int size) throws IOException {
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new BufferedInputStream(Files.newInputStream(tmp)), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return new Preview(columns, rows, 0);
            columns.addAll(parseCsvLine(header));
            int from = (page - 1) * size;
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (idx >= from && rows.size() < size) {
                    List<String> vals = parseCsvLine(line);
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 0; c < columns.size(); c++) row.put(columns.get(c), c < vals.size() ? vals.get(c) : "");
                    rows.add(row);
                }
                idx++;
                if (idx >= MAX_TOTAL) break;
            }
            total = idx;
        }
        return new Preview(columns, rows, total);
    }

    private List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quote = false;
                } else cur.append(ch);
            } else if (ch == '"') quote = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private Preview previewJsonl(Path tmp, int page, int size) throws IOException {
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new BufferedInputStream(Files.newInputStream(tmp)), StandardCharsets.UTF_8))) {
            int from = (page - 1) * size;
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                total++;
                if (idx < from || rows.size() >= size) { idx++; continue; }
                Map<String, Object> row = parseJsonObject(line, columns);
                if (row != null) rows.add(row);
                idx++;
                if (total >= MAX_TOTAL) break;
            }
        }
        if (columns.isEmpty()) columns.add("value");
        return new Preview(columns, rows, total);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String line, List<String> columns) {
        try {
            Map<String, Object> obj = objectMapper.readValue(line, Map.class);
            if (obj == null) return null;
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                if (!columns.contains(e.getKey())) columns.add(e.getKey());
                row.put(e.getKey(), e.getValue());
            }
            return row;
        } catch (Exception e) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", line);
            if (!columns.contains("value")) columns.add("value");
            return row;
        }
    }

    private Preview previewJsonArray(Path tmp, int page, int size) throws IOException {
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        try (InputStream in = Files.newInputStream(tmp)) {
            List<Object> arr = objectMapper.readValue(in, List.class);
            if (arr == null) return new Preview(columns, rows, 0);
            total = Math.min(arr.size(), MAX_TOTAL);
            int from = (page - 1) * size;
            for (int i = from; i < Math.min(from + size, (int) total); i++) {
                Object o = arr.get(i);
                if (o instanceof Map<?, ?> mp) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : mp.entrySet()) {
                        String k = String.valueOf(e.getKey());
                        if (!columns.contains(k)) columns.add(k);
                        row.put(k, e.getValue());
                    }
                    rows.add(row);
                } else {
                    if (!columns.contains("value")) columns.add("value");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("value", o);
                    rows.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("解析 JSON 数组失败: {}", e.getMessage());
        }
        return new Preview(columns, rows, total);
    }

    private Preview previewTextFallback(Path tmp, int page, int size) throws IOException {
        List<String> columns = new ArrayList<>(List.of("line"));
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new BufferedInputStream(Files.newInputStream(tmp)), StandardCharsets.UTF_8))) {
            int from = (page - 1) * size;
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                total++;
                if (idx >= from && rows.size() < size) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("line", line);
                    rows.add(row);
                }
                idx++;
                if (total >= MAX_TOTAL) break;
            }
        } catch (Exception ignore) {}
        return new Preview(columns, rows, total);
    }
}
