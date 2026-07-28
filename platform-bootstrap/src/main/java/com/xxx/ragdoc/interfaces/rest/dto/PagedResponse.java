package com.xxx.ragdoc.interfaces.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 通用分页响应包装(规避引入 PageResponse 类)。 */
@Schema(name = "PagedResponse")
public record PagedResponse<T>(List<T> items, long total, int page, int size) {
    public static <T> PagedResponse<T> of(List<T> items, long total, int page, int size) {
        return new PagedResponse<>(items, total, page, size);
    }
}
