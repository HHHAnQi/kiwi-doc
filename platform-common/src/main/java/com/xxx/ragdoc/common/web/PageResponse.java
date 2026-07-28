package com.xxx.ragdoc.common.web;

import java.util.List;

/** 列表分页响应通用包装。 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {}
