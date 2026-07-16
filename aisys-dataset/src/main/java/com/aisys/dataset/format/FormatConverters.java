package com.aisys.dataset.format;

import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 格式转换器：读取 JSONL 版本数据流，按 targetFormat 输出。
 * <p>支持 csv（通用）、coco/yolo（目标检测）、voc（Pascal VOC XML）。
 * <p>JSONL 每行为一个 JSON 对象，检测任务字段约定：
 * {file, width, height, annotations:[{category_id, category_name, bbox:[cx,cy,w,h]}]}
 * <p>实现均为流式逐行处理（不一次性读入全部行），避免大数据集导出 OOM。
 * 其中 csv/yolo/voc 输出逐行增量，内存有界；coco 因是单个 JSON 文档需累计 images/annotations，
 * 内存随数据集规模增长（超大检测集建议分片导出）。
 */
public class FormatConverters {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 主入口：根据 targetFormat 流式转换 jsonl → OutputStream */
    public static void convert(InputStream jsonlIn, OutputStream out, String targetFormat) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(jsonlIn, StandardCharsets.UTF_8));
        String tf = targetFormat == null ? "" : targetFormat.toLowerCase().trim();
        switch (tf) {
            case "csv" -> streamCsv(reader, out);
            case "coco" -> streamCoco(reader, out);
            case "yolo" -> streamYolo(reader, out);
            case "voc" -> streamVoc(reader, out);
            case "raw", "jsonl", "" -> streamRaw(reader, out);
            default -> throw new IOException("不支持的目标格式: " + tf
                    + "（仅支持 csv/coco/yolo/voc/raw）");
        }
        out.flush();
    }

    /** 逐行解析 JSONL（跳过空行/非法行）。返回 null 表示流结束。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readRow(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                Map<String, Object> obj = mapper.readValue(line, Map.class);
                if (obj != null) return obj;
            } catch (Exception ignored) {
                // 非法 JSON 行跳过
            }
        }
        return null;
    }

    // ======================== CSV（流式） ========================
    private static void streamCsv(BufferedReader reader, OutputStream out) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        List<String> headers = new ArrayList<>();
        boolean headerWritten = false;
        Map<String, Object> row;
        while ((row = readRow(reader)) != null) {
            if (!headerWritten) {
                headers.addAll(row.keySet());
                w.write(String.join(",", headers));
                w.newLine();
                headerWritten = true;
            }
            List<String> vals = new ArrayList<>();
            for (String h : headers) {
                Object val = row.get(h);
                String s = val == null ? "" : String.valueOf(val).replace("\"", "\"\"");
                if (s.contains(",") || s.contains("\"") || s.contains("\n")) s = "\"" + s + "\"";
                vals.add(s);
            }
            w.write(String.join(",", vals));
            w.newLine();
        }
        if (!headerWritten) out.write(new byte[0]);
        w.flush();
    }

    // ======================== COCO（累计单 JSON 文档） ========================
    @SuppressWarnings("unchecked")
    private static void streamCoco(BufferedReader reader, OutputStream out) throws IOException {
        List<Map<String, Object>> images = new ArrayList<>();
        List<Map<String, Object>> annotations = new ArrayList<>();
        Map<Integer, String> categories = new LinkedHashMap<>();
        int annId = 1;
        int i = 0;
        Map<String, Object> row;
        while ((row = readRow(reader)) != null) {
            int imgId = ++i;
            Map<String, Object> img = new LinkedHashMap<>();
            img.put("id", imgId);
            img.put("file_name", str(row, "file", str(row, "image", str(row, "file_path", ""))));
            img.put("width", num(row, "width", 0));
            img.put("height", num(row, "height", 0));
            images.add(img);

            Object annsObj = row.get("annotations");
            if (annsObj instanceof List<?> anns) {
                for (Object aObj : anns) {
                    if (!(aObj instanceof Map<?, ?>)) continue;
                    Map<String, Object> a = (Map<String, Object>) aObj;
                    int catId = (int) num(a, "category_id", 0);
                    String catName = str(a, "category_name", str(a, "label", "class_" + catId));
                    categories.putIfAbsent(catId, catName);

                    Map<String, Object> ann = new LinkedHashMap<>();
                    ann.put("id", annId++);
                    ann.put("image_id", imgId);
                    ann.put("category_id", catId);
                    List<Double> bbox = extractBbox(a);
                    ann.put("bbox", bbox);
                    ann.put("area", bbox.size() >= 4 ? bbox.get(2) * bbox.get(3) : 0.0);
                    ann.put("iscrowd", 0);
                    annotations.add(ann);
                }
            }
        }

        List<Map<String, Object>> catList = new ArrayList<>();
        for (var e : categories.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", e.getKey());
            c.put("name", e.getValue());
            c.put("supercategory", "none");
            catList.add(c);
        }

        Map<String, Object> coco = new LinkedHashMap<>();
        coco.put("images", images);
        coco.put("annotations", annotations);
        coco.put("categories", catList);
        out.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(coco));
    }

    // ======================== YOLO（流式文本） ========================
    @SuppressWarnings("unchecked")
    private static void streamYolo(BufferedReader reader, OutputStream out) throws IOException {
        Map<Integer, String> categories = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        Map<String, Object> row;
        while ((row = readRow(reader)) != null) {
            Object annsObj = row.get("annotations");
            if (!(annsObj instanceof List<?> anns)) continue;
            double imgW = num(row, "width", 0);
            double imgH = num(row, "height", 0);
            if (imgW <= 0 || imgH <= 0) continue; // 缺图像尺寸无法归一化到 0-1
            for (Object aObj : anns) {
                if (!(aObj instanceof Map<?, ?>)) continue;
                Map<String, Object> a = (Map<String, Object>) aObj;
                int catId = (int) num(a, "category_id", 0);
                categories.putIfAbsent(catId, str(a, "category_name", "class_" + catId));
                List<Double> bbox = extractBbox(a);
                if (bbox.size() < 4) continue;
                double x = bbox.get(0), y = bbox.get(1), w = bbox.get(2), h = bbox.get(3);
                double cx = (x + w / 2) / imgW;
                double cy = (y + h / 2) / imgH;
                body.append(catId).append(' ')
                        .append(String.format("%.6f", cx)).append(' ')
                        .append(String.format("%.6f", cy)).append(' ')
                        .append(String.format("%.6f", w / imgW)).append(' ')
                        .append(String.format("%.6f", h / imgH)).append('\n');
            }
        }
        StringBuilder result = new StringBuilder();
        result.append("# classes.txt\n");
        for (var e : categories.entrySet()) result.append(e.getValue()).append('\n');
        result.append("\n# labels\n");
        result.append(body);
        out.write(result.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ======================== VOC（流式 XML，直接写 OutputStream） ========================
    @SuppressWarnings("unchecked")
    private static void streamVoc(BufferedReader reader, OutputStream out) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        w.write("<annotations>\n");
        int i = 0;
        Map<String, Object> row;
        while ((row = readRow(reader)) != null) {
            String file = str(row, "file", str(row, "image", "image_" + (++i) + ".jpg"));
            int wv = (int) num(row, "width", 0);
            int hv = (int) num(row, "height", 0);
            w.write("  <annotation>\n");
            w.write("    <filename>" + escXml(file) + "</filename>\n");
            w.write("    <size>\n");
            w.write("      <width>" + wv + "</width>\n");
            w.write("      <height>" + hv + "</height>\n");
            w.write("      <depth>3</depth>\n");
            w.write("    </size>\n");

            Object annsObj = row.get("annotations");
            if (annsObj instanceof List<?> anns) {
                for (Object aObj : anns) {
                    if (!(aObj instanceof Map<?, ?>)) continue;
                    Map<String, Object> a = (Map<String, Object>) aObj;
                    List<Double> bbox = extractBbox(a);
                    if (bbox.size() < 4) continue;
                    double x = bbox.get(0), y = bbox.get(1), bw = bbox.get(2), bh = bbox.get(3);
                    w.write("    <object>\n");
                    w.write("      <name>" + escXml(str(a, "category_name", "class_" + (int) num(a, "category_id", 0))) + "</name>\n");
                    w.write("      <bndbox>\n");
                    w.write("        <xmin>" + (int) x + "</xmin>\n");
                    w.write("        <ymin>" + (int) y + "</ymin>\n");
                    w.write("        <xmax>" + (int) (x + bw) + "</xmax>\n");
                    w.write("        <ymax>" + (int) (y + bh) + "</ymax>\n");
                    w.write("      </bndbox>\n");
                    w.write("    </object>\n");
                }
            }
            w.write("  </annotation>\n");
        }
        w.write("</annotations>\n");
        w.flush();
    }

    private static void streamRaw(BufferedReader reader, OutputStream out) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        Map<String, Object> row;
        while ((row = readRow(reader)) != null) {
            w.write(mapper.writeValueAsString(row));
            w.newLine();
        }
        w.flush();
    }

    // ======================== helpers ========================
    @SuppressWarnings("unchecked")
    private static List<Double> extractBbox(Map<String, Object> a) {
        Object bboxObj = a.get("bbox");
        if (bboxObj instanceof List<?> list) {
            List<Double> result = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Number) result.add(((Number) o).doubleValue());
            }
            return result;
        }
        return List.of();
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v != null) { try { return Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {} }
        return def;
    }

    private static String escXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
