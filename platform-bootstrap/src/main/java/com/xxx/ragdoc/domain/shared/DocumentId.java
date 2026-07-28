package com.xxx.ragdoc.domain.shared;

/** Document 主键值对象。包装 {@code Long} 防基础类型混淆。 */
public record DocumentId(Long value) {
    public DocumentId {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("DocumentId 必须 > 0");
        }
    }
}
