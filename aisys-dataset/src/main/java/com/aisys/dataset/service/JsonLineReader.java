package com.aisys.dataset.service;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSONL 单行解析工具（DDD 5.4）。
 * <p>使用 Jackson 3（tools.jackson.databind.ObjectMapper）。逐行解析 jsonl，避免一次性加载大文件。
 */
final class JsonLineReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonLineReader() {}

    /** 解析单行为 Map；解析失败抛 RuntimeException（由调用方决定是否吞掉）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readObject(String json) {
        return MAPPER.readValue(json, Map.class);
    }

    /** 解析任意 JSON 值（对象/数组/标量）。 */
    static Object readAny(String json) {
        return MAPPER.readValue(json, Object.class);
    }
}
