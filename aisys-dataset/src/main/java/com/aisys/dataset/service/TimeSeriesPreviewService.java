package com.aisys.dataset.service;

import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.exception.CommonErrorCode;
import com.aisys.common.s3.service.S3StorageService;
import com.aisys.dataset.dto.DatasetVersionDtos.*;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** CSV/TSV signals: an archive entry is a sample, numeric columns are channels. */
@Service
public class TimeSeriesPreviewService {
    private final S3StorageService s3;
    public TimeSeriesPreviewService(S3StorageService s3) { this.s3 = s3; }
    public record UploadSummary(long samples, List<String> columns) {}
    private record SampleFile(int index, String path, String split, String label, long size) {}
    private static final List<String> LIST_COLUMNS = List.of("id", "file", "label", "split", "sizeBytes");

    public Preview preview(String key, int page, int size, String split, String label, String keyword) {
        return withObject(key, path -> {
            List<SampleFile> all = files(path, key);
            String query = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT).trim();
            List<SampleFile> filtered = all.stream().filter(s -> split == null || split.isBlank() || s.split().equals(split))
                    .filter(s -> label == null || label.isBlank() || s.label().equals(label))
                    .filter(s -> query.isEmpty() || s.path().toLowerCase(Locale.ROOT).contains(query)).toList();
            int pageSize = Math.max(1, Math.min(size, 200));
            long from = (long) (Math.max(page, 1) - 1) * pageSize;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = (int) Math.min(from, filtered.size()); i < Math.min(from + pageSize, filtered.size()); i++) {
                SampleFile sample = filtered.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", sample.index() + 1); row.put("file", simpleName(sample.path()));
                row.put("path", sample.path()); row.put("label", sample.label());
                row.put("split", sample.split()); row.put("sizeBytes", sample.size());
                rows.add(row);
            }
            return new Preview(LIST_COLUMNS, rows, filtered.size());
        });
    }

    public TimeSeriesSample sample(String key, int index) {
        return withObject(key, path -> {
            List<SampleFile> files = files(path, key);
            if (index < 0 || index >= files.size()) throw bad("时序样本序号超出范围");
            return readSample(path, files.get(index));
        });
    }

    public Statistics statistics(String key, Long archiveSize, String checksum) {
        return withObject(key, path -> {
            List<SampleFile> files = files(path, key);
            Map<String, Long> classes = new LinkedHashMap<>(), splits = new LinkedHashMap<>();
            for (SampleFile sample : files) {
                if (!sample.label().equals("-")) classes.merge(sample.label(), 1L, Long::sum);
                splits.merge(sample.split(), 1L, Long::sum);
            }
            long totalSize = Files.size(path);
            int fileCount = 1;
            if (isZip(path)) {
                try (ZipFile zip = ZipArchives.open(path)) {
                    totalSize = 0; fileCount = 0;
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (!entry.isDirectory()) { fileCount++; totalSize += Math.max(0, entry.getSize()); }
                    }
                }
            }
            List<Map<String, String>> columns = files.isEmpty() ? List.of() : columns(readSample(path, files.getFirst())).stream()
                    .map(name -> Map.of("name", name, "type", "number")).toList();
            return new Statistics((long) files.size(), archiveSize, columns, classes, checksum,
                    (long) files.size(), fileCount, totalSize, classes.isEmpty() ? null : classes.size(),
                    null, null, splits, null, null);
        });
    }

    /** Used at upload completion, before a version becomes ready. */
    public UploadSummary inspect(InputStream input, String fileName) throws IOException {
        Path tmp = Files.createTempFile("ds-signal-inspect-", ".bin");
        try {
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING);
            List<SampleFile> files = files(tmp, fileName);
            return new UploadSummary(files.size(), files.isEmpty() ? List.of() : columns(readSample(tmp, files.getFirst())));
        } finally { Files.deleteIfExists(tmp); }
    }

    private List<String> columns(TimeSeriesSample sample) {
        List<String> columns = new ArrayList<>();
        if (!sample.axisName().equals("sample_index")) columns.add(sample.axisName());
        sample.channels().forEach(c -> columns.add(c.name()));
        return columns;
    }

    private List<SampleFile> files(Path path, String key) throws IOException {
        if (!isZip(path)) return List.of(new SampleFile(0, simpleName(key), "-", "-", Files.size(path)));
        List<String> names = new ArrayList<>();
        try (ZipFile zip = ZipArchives.open(path)) {
            zip.stream().filter(e -> !e.isDirectory() && signalFile(e.getName())).forEach(e -> names.add(e.getName()));
            names.sort(String::compareTo);
            List<SampleFile> out = new ArrayList<>(names.size());
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                String[] parts = name.replace('\\', '/').split("/");
                String split = "-";
                for (String part : parts) {
                    if (part.equalsIgnoreCase("train")) split = "train";
                    if (part.equalsIgnoreCase("val") || part.equalsIgnoreCase("validation")) split = "val";
                    if (part.equalsIgnoreCase("test")) split = "test";
                }
                String label = parts.length > 1 ? parts[parts.length - 2] : "-";
                if (List.of("train", "val", "validation", "test").contains(label.toLowerCase(Locale.ROOT))) label = "-";
                out.add(new SampleFile(i, name, split, label, zip.getEntry(name).getSize()));
            }
            if (out.isEmpty()) throw bad("时序归档中未找到 CSV/TSV 信号样本");
            return out;
        }
    }

    private boolean signalFile(String name) {
        String n = name.replace('\\', '/').toLowerCase(Locale.ROOT);
        String base = simpleName(n);
        return (n.endsWith(".csv") || n.endsWith(".tsv")) && !n.contains("__macosx/") && !base.startsWith("._")
                && !Set.of("metadata.csv", "manifest.csv", "labels.csv", "index.csv").contains(base);
    }

    private TimeSeriesSample readSample(Path path, SampleFile file) throws IOException {
        byte[] bytes;
        if (isZip(path)) {
            try (ZipFile zip = ZipArchives.open(path); InputStream in = zip.getInputStream(zip.getEntry(file.path()))) {
                bytes = in.readAllBytes();
            }
        } else bytes = Files.readAllBytes(path);
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException ex) { text = Charset.forName("GB18030").decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        text = text.replaceFirst("^\\uFEFF", "");
        List<String> lines = text.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) throw bad("该时序样本为空");
        char delimiter = lines.getFirst().contains("\t") ? '\t' : ',';
        List<String> first = splitLine(lines.getFirst(), delimiter);
        boolean header = first.stream().anyMatch(value -> !value.isBlank() && number(value) == null);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) headers.add(header ? first.get(i).trim() : "channel_" + (i + 1));
        if (new HashSet<>(headers).size() != headers.size()) throw bad("信号文件包含重复列名");
        int axisColumn = -1;
        Set<String> axes = Set.of("t", "time", "timestamp", "index", "sample", "sample_index", "point", "采样点", "时间");
        for (int i = 0; i < headers.size(); i++) if (axes.contains(headers.get(i).toLowerCase(Locale.ROOT))) { axisColumn = i; break; }
        List<List<Double>> values = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) values.add(new ArrayList<>());
        for (int row = header ? 1 : 0; row < lines.size(); row++) {
            List<String> cells = splitLine(lines.get(row), delimiter);
            for (int col = 0; col < headers.size(); col++) values.get(col).add(col < cells.size() ? number(cells.get(col)) : null);
        }
        int count = values.getFirst().size();
        if (count == 0) throw bad("信号文件只有表头，没有采样点");
        List<Double> axis = new ArrayList<>(count);
        int missingAxis = 0;
        for (int i = 0; i < count; i++) {
            Double value = axisColumn < 0 ? Double.valueOf(i) : values.get(axisColumn).get(i);
            if (value == null) { value = (double) i; missingAxis++; }
            axis.add(value);
        }
        List<SignalChannel> channels = new ArrayList<>();
        for (int col = 0; col < headers.size(); col++) {
            String name = headers.get(col);
            if (col != axisColumn && !Set.of("label", "class", "target", "split").contains(name.toLowerCase(Locale.ROOT))) {
                channels.add(channel(name, values.get(col)));
            }
        }
        if (channels.isEmpty() || channels.stream().allMatch(c -> c.validCount() == 0)) throw bad("未找到可绘制的数值信号通道");
        String axisName = axisColumn < 0 ? "sample_index" : headers.get(axisColumn);
        boolean sampleAxis = axisColumn < 0 || Set.of("index", "sample", "sample_index", "point", "采样点").contains(axisName.toLowerCase(Locale.ROOT));
        if (axisName.equalsIgnoreCase("t")) sampleAxis = java.util.stream.IntStream.range(0, count).allMatch(i -> axis.get(i) == i + axis.getFirst());
        List<String> warnings = new ArrayList<>();
        if (missingAxis > 0) {
            axisName = "sample_index";
            sampleAxis = true;
            for (int i = 0; i < count; i++) axis.set(i, (double) i);
            warnings.add("横轴包含 " + missingAxis + " 个无效值，已改用采样点序号；请核对原始文件");
        }
        return new TimeSeriesSample(file.index() + 1, simpleName(file.path()), file.path(), file.split(), file.label(), count,
                axisName, sampleAxis ? "index" : "time", axis, channels, warnings);
    }

    private SignalChannel channel(String name, List<Double> values) {
        long n = 0; double mean = 0, m2 = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        double scale = 0, scaledSquares = 0;
        for (Double value : values) {
            if (value == null) continue;
            n++; double delta = value - mean; mean += delta / n; m2 += delta * (value - mean);
            min = Math.min(min, value); max = Math.max(max, value);
            double abs = Math.abs(value);
            if (abs > scale) { scaledSquares = scale == 0 ? 1 : scaledSquares * Math.pow(scale / abs, 2) + 1; scale = abs; }
            else if (scale > 0) scaledSquares += Math.pow(abs / scale, 2);
        }
        return new SignalChannel(name, values, n, values.size() - n, n == 0 ? null : finite(min), n == 0 ? null : finite(max),
                n == 0 ? null : finite(mean), n == 0 ? null : finite(Math.sqrt(Math.max(0, m2 / n))),
                n == 0 ? null : finite(scale * Math.sqrt(scaledSquares / n)));
    }

    private Double finite(double value) { return Double.isFinite(value) ? value : null; }
    private Double number(String value) {
        try { return finite(Double.parseDouble(value.trim())); } catch (RuntimeException ex) { return null; }
    }
    private List<String> splitLine(String line, char delimiter) {
        List<String> out = new ArrayList<>(); StringBuilder cell = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == delimiter && !quoted) { out.add(cell.toString()); cell.setLength(0); }
            else cell.append(c);
        }
        out.add(cell.toString()); return out;
    }
    private boolean isZip(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) { return in.read() == 'P' && in.read() == 'K'; }
    }
    private String simpleName(String path) { String n = path.replace('\\', '/'); return n.substring(n.lastIndexOf('/') + 1); }
    private BusinessException bad(String message) { return new BusinessException(CommonErrorCode.BAD_REQUEST, message); }
    private interface FileAction<T> { T apply(Path path) throws IOException; }
    private <T> T withObject(String key, FileAction<T> action) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ds-signal-preview-", ".bin");
            try (InputStream in = s3.getStream(s3.bucket(), key)) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
            return action.apply(tmp);
        } catch (IOException ex) { throw new BusinessException(CommonErrorCode.BAD_REQUEST, "读取时序信号失败：" + ex.getMessage()); }
        finally { if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
    }
}
