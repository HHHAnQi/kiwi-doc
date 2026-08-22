package com.xxx.ragdoc.application.document.chunking;

import java.util.List;

/**
 * Contextual Retrieval 前缀构造器 (Anthropic 2024 contextual embeddings 的确定性轻量版)。
 *
 * <p>问题: 脱离文档上下文的 chunk 向量语义失真 — "默认端口是 10911" 这样的 chunk 单独 embed 时,
 * "默认端口"无法与 RocketMQ 关联, 检索 "RocketMQ 默认端口" 靠 parent/全局语义碰运气。
 *
 * <p>方案: embed 输入前拼接确定性上下文前缀(文档标题 + section 路径 + 来源组件), 让每个 chunk
 * 的向量携带自身出处。Anthropic 原版用 LLM 生成每 chunk 的 context blurb(质量更高但入库成本 ×N
 * LLM 调用); 本实现复用已有 section_path 元数据, 零额外 LLM 成本。
 *
 * <p>关键边界:
 *
 * <ul>
 *   <li>只影响 <b>embedding 输入</b>; MySQL chunk.content / contentHash / Milvus text(BM25 输入)
 *       / 前端 snippet 全部保持原文 — 检索命中后展示与引用不受影响
 *   <li>query 侧不拼接(与 Anthropic 实践一致: 只增强 document 侧表示)
 *   <li>已有向量不受影响, 新上传/reparse 的文档才生效; 全量重建走 scripts/reindex_milvus.py
 * </ul>
 */
public final class ContextualEmbeddingPrefix {

    private ContextualEmbeddingPrefix() {}

    /**
     * 构造 chunk 的上下文前缀。
     *
     * @param docTitle 文档标题(通常为 originalFilename 去扩展名), 可空
     * @param source 来源组件(dubbo/nacos/...), 可空
     * @param sectionPath markdown heading 路径栈, 可空
     * @param maxChars 前缀长度上限(含边界), &lt;=0 觔回空串
     */
    public static String build(
            String docTitle, String source, List<String> sectionPath, int maxChars) {
        if (maxChars <= 0) return "";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (source != null && !source.isBlank() && !"unknown".equals(source)) {
            sb.append("来源: ").append(source.trim());
            first = false;
        }
        if (docTitle != null && !docTitle.isBlank()) {
            if (!first) sb.append(" | ");
            sb.append("文档: ").append(stripExtension(docTitle.trim()));
            first = false;
        }
        if (sectionPath != null && !sectionPath.isEmpty()) {
            if (!first) sb.append(" | ");
            sb.append("章节: ").append(String.join(" › ", sectionPath));
            first = false;
        }
        if (first) return ""; // 无任何元数据, 不加前缀(等价 baseline)
        sb.append("]\n");
        String prefix = sb.toString();
        return prefix.length() <= maxChars ? prefix : prefix.substring(0, maxChars);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        // 仅剥最后一个扩展名, 且防 ".gitignore" 这种整个是扩展名的名字
        return (dot > 0 && dot < filename.length() - 1) ? filename.substring(0, dot) : filename;
    }
}
