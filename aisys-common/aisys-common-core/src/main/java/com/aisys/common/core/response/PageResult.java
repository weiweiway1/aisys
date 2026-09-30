package com.aisys.common.core.response;

import java.util.List;

/**
 * 分页结果体（DDD 3.2）。配合 Mapper 手写 LIMIT/OFFSET + count 查询，由 Service 组装。
 * 字段名 items 与前端 ResultTable.data.items 对齐（DDD 6.2）。
 */
public record PageResult<T>(List<T> items, long total, int page, int size, int totalPages) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResult<>(items == null ? List.of() : items, total, page, size, totalPages);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return of(List.of(), 0, page, size);
    }
}
