package com.xxx.ragdoc.domain.document;

/**
 * chunk 在 PDF 页面上的边界框([x1, y1, x2, y2],PDF 点坐标)。
 * 可空:Markdown 等无 bbox 的格式不要求。
 */
public record BoundingBox(double x1, double y1, double x2, double y2) {
    public BoundingBox {
        if (x2 < x1 || y2 < y1) {
            throw new IllegalArgumentException("BoundingBox 非法: x2/y2 必须大于 x1/y1");
        }
    }
}
