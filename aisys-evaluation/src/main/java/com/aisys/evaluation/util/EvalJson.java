package com.aisys.evaluation.util;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * JSON 工具（Jackson 3）。封装 List/Map 与 JSON 字符串互转，供 Service 在 JSONB 列与 DTO 之间转换。
 */
public final class EvalJson {

    private EvalJson() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final TypeReference<List<Long>> LIST_LONG = new TypeReference<>() {};
    public static final TypeReference<Map<String, Object>> MAP_OBJ = new TypeReference<>() {};

    public static String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            return null;
        }
    }

    public static List<Long> toLongList(String json) {
        return fromJson(json, LIST_LONG);
    }

    public static Map<String, Object> toMap(String json) {
        return fromJson(json, MAP_OBJ);
    }
}
