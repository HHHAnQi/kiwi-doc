package com.xxx.ragdoc.domain.document;

/** chunk 类型。与 docs/data/data-model.md 一致。 */
public enum ChunkType {
    TEXT("文本块"),
    TABLE("表格,转 HTML"),
    FIGURE("图片,可附 VLM caption"),
    TITLE("标题"),
    CODE("代码块"),
    LIST("列表"),
    /** Parent-Child 切片的大块(用于 LLM context 回链, 不入向量索引) */
    PARENT("Parent-Child 模式的 parent 全文块"),
    /** Parent-Child 切片的小块(入向量索引, 检索精准) */
    CHILD("Parent-Child 模式的 child 检索块");

    private final String description;

    ChunkType(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
