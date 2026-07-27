package com.xxx.ragdoc.domain.document;

/**
 * chunk 类型。与 docs/data/data-model.md 一致。
 */
public enum ChunkType {
    TEXT("文本块"),
    TABLE("表格,转 HTML"),
    FIGURE("图片,可附 VLM caption"),
    TITLE("标题"),
    CODE("代码块"),
    LIST("列表");

    private final String description;

    ChunkType(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
