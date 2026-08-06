package com.xxx.ragdoc.application.chat.comparison;

/**
 * PR-6c / EMS-PR6c §7.1: 比较侧别 — left / right, 用于 Evidence provenance 与文案模板。
 *
 * <p>{@code label()} 返回客户端可读字符串 ("左" / "右"), 主要供 Prompt 模板与 Trace。
 */
public enum ComparisonSide {
    LEFT("左"),
    RIGHT("右");

    private final String displayLabel;

    ComparisonSide(String label) {
        this.displayLabel = label;
    }

    public String displayLabel() {
        return displayLabel;
    }
}
