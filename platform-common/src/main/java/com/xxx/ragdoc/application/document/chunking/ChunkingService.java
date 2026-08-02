package com.xxx.ragdoc.application.document.chunking;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 文本切片器, 双模式:
 *
 * <h2>模式 1: 同质 chunk(默认, V2 起点路径)</h2>
 *
 * 单层 token-based 切片(800 字符 + 80 overlap + 句边界优化), 所有 chunk 无 parent 关系。 调 {@link #chunk(String)}。
 *
 * <h2>模式 2: Parent-Child(消融 D 档, P3-A)</h2>
 *
 * 参考 LlamaIndex {@code HierarchicalNodeParser} 设计:
 *
 * <ol>
 *   <li>段落感知分割: 全文按连续换行(段落边界)切成 raw paragraphs
 *   <li>paragraph 聚合成 parent(目标 2000 字符, 段落不切断)
 *   <li>parent 内部按句号切成 child(目标 400 字符)
 *   <li>child 入 Milvus 索引(检索准); parent 全文喂 LLM(context 全)
 * </ol>
 *
 * <p>设计动因: badcase 实测 150 个 contexts 中 100% &lt; 300 字符, 平均 190 字符 → context_recall≈0.55, 根因是"小
 * chunk 信息不全"。Parent-Child 直接解决: child 检索保证准, parent 回链保证全。
 *
 * <p>切换: feature flag {@code rag.chunking.mode: flat|parent_child}(默认 flat, 向后兼容)。
 *
 * <p>V1 stub 用的固定长度 char 切片太粗糙, V2 改为 token-based; V2 末(P3-A) 加 Parent-Child; V2-P4(Q3-A) 加
 * Markdown 结构感知 + child overlap。
 *
 * <h2>Q3-A 改造(V2-P4): 结构感知 + child overlap</h2>
 *
 * 原实现按字数 + 句号/换行细切, 会粗暴切断 code block 中间的多行 SQL/YAML 与 markdown table 中间的表体。 现在先走 {@link
 * MarkdownStructurer#parse} 把全文分解为 TEXT/CODE/TABLE 块, 切片时:
 *
 * <ul>
 *   <li>CODE / TABLE 块作为<b>原子单元</b>整体保留, 不被字数边界切断(除非单块超 CHUNK_CHARS 上限, 才硬切)
 *   <li>parent-child 模式下 child 之间补 {@link #CHILD_OVERLAP_CHARS} 字符重叠, 避免边缘 token 召回丢失
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ChunkingService {

    private final TextCleaner textCleaner;

    /** 模式 1 同质切片参数 */
    private static final int CHUNK_CHARS = 800;

    private static final int OVERLAP_CHARS = 80;
    private static final int MIN_CHUNK_CHARS = 50;

    /** 模式 2 Parent-Child 参数(参考 LlamaIndex HierarchicalNodeParser 默认值) */
    private static final int PARENT_TARGET_CHARS = 2000;

    private static final int PARENT_MIN_CHARS = 200; // 短于这个不分独立 parent, 并入下一个
    private static final int CHILD_TARGET_CHARS = 400;
    private static final int CHILD_MIN_CHARS = 50;

    /** Q3-A: child 之间 overlap 字符数(~10% of CHILD_TARGET)。原 0 → 边缘 token 召回丢失。 */
    private static final int CHILD_OVERLAP_CHARS = 40;

    /**
     * 模式 1: 把全文切成带重叠的同质 chunks(feature flag=flat 时调用)。
     *
     * <p>Q3-A: 改用"结构感知 + 块级原子"切片。
     *
     * @deprecated Q3-B: 改用 {@link #chunkSectioned(String)} —— 它返回带 section_path 的结果, citation
     *     溯源必备。本方法保留作内部向后兼容(测试与未来外部调用方), 生产路径(TikaParsingTrigger)已切到 sectioned 版。 未来 V3 拆
     *     parser-service 时若再无调用方可一并删除。
     * @param fullText 文档解析后的全文(Tika 抽出, 未清洗)
     * @return chunk 内容列表(顺序保证), 每条对应一个 chunk
     */
    @Deprecated
    public List<String> chunk(String fullText) {
        // Q3-B: 委托 sectioned 版本, 只取 text 部分(向后兼容老调用方)
        return chunkSectioned(fullText).stream().map(SectionedFlatChunk::text).toList();
    }

    /**
     * 模式 1 的 sectioned 版: 每条 chunk 自带 section_path(Q3-B)。
     *
     * @return chunk 列表, 每条带它所属块的 heading 路径栈(可能为空 list)
     */
    public List<SectionedFlatChunk> chunkSectioned(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return List.of();
        }

        // Q3-A: 先结构化, 再按块逐类处理, 避免 code/table 被句号/换行切断
        List<MarkdownStructurer.StructuredBlock> blocks = MarkdownStructurer.parse(fullText);
        if (blocks.isEmpty()) {
            // 退化处理: 老路径保护(理论上 MarkdownStructurer 永远至少产出 1 个 TEXT 块)
            String cleaned = textCleaner.clean(fullText);
            if (cleaned.isBlank()) return List.of();
            return splitFlatText(cleaned).stream()
                    .map(t -> new SectionedFlatChunk(t, List.of()))
                    .toList();
        }

        List<SectionedFlatChunk> result = new ArrayList<>();
        for (MarkdownStructurer.StructuredBlock block : blocks) {
            switch (block.type()) {
                case CODE, TABLE -> {
                    // 原子单元: 整段塞入, 不切也不走 TextCleaner。
                    // 关键: code block / table 内容是干净的语义数据(配置/SQL/表体),
                    // 不能让 TextCleaner 的 "<[^>]+>" 规则误清掉 XML/YAML 标签。
                    // 不切(TikaParsingTrigger 的 MAX_TEXT_LENGTH 上限兜底超大块)
                    String content = block.content();
                    if (!content.isBlank()) {
                        result.add(new SectionedFlatChunk(content, block.sectionPath()));
                    }
                }
                case TEXT -> {
                    // 纯文本: 经 TextCleaner 后走原算法; 同块切出的多 chunk 共享该块 sectionPath
                    String cleaned = textCleaner.clean(block.content());
                    if (!cleaned.isBlank()) {
                        for (String t : splitFlatText(cleaned)) {
                            result.add(new SectionedFlatChunk(t, block.sectionPath()));
                        }
                    }
                }
            }
        }
        return result.stream().filter(c -> c.text().trim().length() >= MIN_CHUNK_CHARS).toList();
    }

    /**
     * 模式 2: 把全文切成 Parent-Child 层级结构(feature flag=parent_child 时调用)。
     *
     * <p>两层布局: parent 大块(段落聚合, ~2000 字) → 内部细切 child(~400 字)。返回顺序: 跨 parent 内聚, 即 parent1's
     * children, parent2's children, ...。每条 child 带它所属的 parent 内容(供下游写 parent_chunk_id + parent
     * 文本回链)。
     *
     * <p>Q3-A 改造: 先结构化, code/table 块作为单个 child(不细切), 文本块走 parent 聚合 + child overlap。
     *
     * @deprecated Q3-B: 改用 {@link #chunkParentChildSectioned(String)} —— 它返回带 section_path 的结果,
     *     citation 溯源必备。本方法保留作内部向后兼容(测试与未来外部调用方), 生产路径(TikaParsingTrigger)已切到 sectioned 版。
     */
    @Deprecated
    public List<ParentChildChunk> chunkParentChild(String fullText) {
        return chunkParentChildSectioned(fullText).stream()
                .map(pc -> new ParentChildChunk(pc.childText(), pc.parentText(), pc.parentIndex()))
                .toList();
    }

    /**
     * 模式 2 的 sectioned 版: 每条 child 自带 section_path(Q3-B)。
     *
     * @return child 列表, 每条带它所属块的 heading 路径栈(可能为空 list)
     */
    public List<SectionedParentChildChunk> chunkParentChildSectioned(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return List.of();
        }
        // Q3-A: 结构化先行, code/table 单独标记不参与"细切"
        List<MarkdownStructurer.StructuredBlock> blocks = MarkdownStructurer.parse(fullText);

        // 1. 把块展开成"段落"(TEXT 块内部按 \n\n 拆, CODE/TABLE 整段一个段落)
        List<BlockParagraph> paragraphs = new ArrayList<>();
        for (MarkdownStructurer.StructuredBlock block : blocks) {
            if (block.type() == MarkdownStructurer.BlockType.CODE
                    || block.type() == MarkdownStructurer.BlockType.TABLE) {
                // 原子块: 不走 TextCleaner(避免 XML/SQL 标签被 HTML 清除规则误伤), 整段一个段落
                String content = block.content();
                if (!content.isBlank()) {
                    paragraphs.add(new BlockParagraph(content, true, block.sectionPath()));
                }
            } else {
                // 文本块: 清洗后按段落分割; 全部子段共享该块 sectionPath
                String cleaned = textCleaner.clean(block.content());
                if (cleaned.isBlank()) continue;
                for (String para : cleaned.split("\\n\\s*\\n")) {
                    String trimmed = para.trim();
                    if (!trimmed.isEmpty()) {
                        paragraphs.add(new BlockParagraph(trimmed, false, block.sectionPath()));
                    }
                }
            }
        }
        if (paragraphs.isEmpty()) return List.of();

        // 2. 段落聚合成 parent(目标 2000 字; 原子段若超出也可独占 parent, 不切断)
        // parent 的 sectionPath 取"该 parent 第一个段落"的 sectionPath
        // Q3-B 关键: 不同 sectionPath 的段不并入同一 parent —— 否则 parent.path 只代表首个 heading,
        // 后续 child 拿不到自己真实章节。让 sectionPath 变化强制开新 parent。
        List<ParentAccumulator> parentTexts = new ArrayList<>();
        ParentAccumulator current = new ParentAccumulator();
        boolean firstPara = true;
        for (BlockParagraph para : paragraphs) {
            boolean join;
            if (current.length() == 0) {
                join = true;
            } else if (para.atomic()) {
                join = false;
            } else if (!para.sectionPath().equals(current.sectionPath)) {
                // Q3-B: section 变了 → 即使还没达 PARENT_TARGET, 也强制开新 parent
                join = false;
            } else {
                join =
                        current.length() + para.text().length() + 1 <= PARENT_TARGET_CHARS
                                || current.length() < PARENT_MIN_CHARS;
            }
            if (!join && current.length() > 0) {
                parentTexts.add(current);
                current = new ParentAccumulator();
                firstPara = true;
            }
            if (firstPara) {
                current.sectionPath = para.sectionPath();
                firstPara = false;
            }
            current.append(para.text());
        }
        flushParentTailSectioned(current, parentTexts);

        // 3. parent 内部切 child
        List<SectionedParentChildChunk> result = new ArrayList<>();
        for (int pIdx = 0; pIdx < parentTexts.size(); pIdx++) {
            ParentAccumulator pa = parentTexts.get(pIdx);
            List<String> children = splitIntoChildren(pa.text());
            for (String child : children) {
                result.add(new SectionedParentChildChunk(child, pa.text(), pIdx, pa.sectionPath));
            }
        }
        return result;
    }

    /** 在 parent 内部细切成 child。Q3-A: 加 overlap, 避免 child 边缘 token 召回丢失。 */
    private List<String> splitIntoChildren(String parentText) {
        List<String> rawChildren = new ArrayList<>();
        String[] sentences = parentText.split("(?<=[。\\.\\!\\?\\n])");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            if (current.length() + s.length() > CHILD_TARGET_CHARS && current.length() > 0) {
                rawChildren.add(current.toString());
                current = new StringBuilder(s);
            } else {
                current.append(s);
            }
        }
        if (current.length() >= CHILD_MIN_CHARS) {
            rawChildren.add(current.toString());
        } else if (!rawChildren.isEmpty() && current.length() > 0) {
            String last = rawChildren.get(rawChildren.size() - 1);
            rawChildren.set(rawChildren.size() - 1, last + current);
        } else if (current.length() > 0) {
            rawChildren.add(current.toString());
        }

        // Q3-A: 相邻 child 补 overlap —— 把前一个 child 的尾部 CHILD_OVERLAP_CHARS 字符回贴到下一个 child 头部
        return applyChildOverlap(rawChildren);
    }

    /**
     * 给相邻 child 之间补 overlap。空列表直接返回; 单元素原样; 多元素把前 child 尾部字符回贴后 child 头部。
     *
     * <p>设计: 前 child 内容<b>不改变</b>(避免污染其语义), 仅后 child 头部多一段前 child 的尾巴。这样: LLM 收到的 context 不重复丢失;
     * 检索时跨 child 边界的 query 能在两侧都召回。
     */
    private static List<String> applyChildOverlap(List<String> children) {
        if (children.size() <= 1) return children;
        List<String> result = new ArrayList<>(children.size());
        result.add(children.get(0));
        for (int i = 1; i < children.size(); i++) {
            String prev = children.get(i - 1);
            String curr = children.get(i);
            if (prev.length() > CHILD_OVERLAP_CHARS) {
                String tail = prev.substring(prev.length() - CHILD_OVERLAP_CHARS);
                result.add(tail + curr);
            } else {
                // 前一个 child 还没 overlap 长, 整段贴上即可
                result.add(prev + curr);
            }
        }
        return result;
    }

    /** 模式 1 的字数 + 句边界切片(抽公共方法供 chunk(TEXT 块) 复用)。 */
    private List<String> splitFlatText(String cleaned) {
        List<String> rawChunks = new ArrayList<>();
        int step = CHUNK_CHARS - OVERLAP_CHARS;
        int len = cleaned.length();
        int pos = 0;

        while (pos < len) {
            int end = Math.min(pos + CHUNK_CHARS, len);
            String chunk = cleaned.substring(pos, end);

            int adjustedEnd = findBreakPoint(cleaned, pos, end, CHUNK_CHARS);
            if (adjustedEnd > pos) {
                chunk = cleaned.substring(pos, adjustedEnd);
            }

            rawChunks.add(chunk);
            int nextPos = adjustedEnd > pos ? adjustedEnd - OVERLAP_CHARS : pos + step;
            if (nextPos <= pos) nextPos = pos + step;
            pos = nextPos;
        }

        return rawChunks.stream().filter(c -> c.trim().length() >= MIN_CHUNK_CHARS).toList();
    }

    /** 在 [start, end] 范围内找一个最近的"自然边界"(换行 / 句号)。 找不到就返回 end。 */
    private static int findBreakPoint(String text, int start, int end, int chunkChars) {
        for (int i = end - 1; i > start + chunkChars / 2; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return i + 1;
        }
        for (int i = end - 1; i > start + chunkChars / 2; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '；' || c == '.') return i + 1;
        }
        return end;
    }

    /** 收尾 flush parent(老路径, 已无人调用, 保留作历史参考)。 */
    @SuppressWarnings("unused")
    private static void flushParentTail(StringBuilder current, List<String> parentTexts) {
        if (current.length() >= PARENT_MIN_CHARS) {
            parentTexts.add(current.toString());
        } else if (!parentTexts.isEmpty() && current.length() > 0) {
            String last = parentTexts.get(parentTexts.size() - 1);
            parentTexts.set(parentTexts.size() - 1, last + "\n\n" + current);
        } else if (current.length() > 0) {
            parentTexts.add(current.toString());
        }
    }

    /** 收尾 flush parent 的 sectioned 版: 处理太短尾部段并入前一个 parent 或独立成 parent。 */
    private static void flushParentTailSectioned(
            ParentAccumulator current, List<ParentAccumulator> parentTexts) {
        if (current.length() >= PARENT_MIN_CHARS) {
            parentTexts.add(current);
        } else if (!parentTexts.isEmpty() && current.length() > 0) {
            ParentAccumulator last = parentTexts.get(parentTexts.size() - 1);
            // Q3-B: section 不同的尾部段绝不并入前 parent — 否则会破坏 parent.path 语义
            // (避免 [Nacos] parent 被并入 [Nacos, 配置管理] 段后 child path 错乱)
            if (last.sectionPath.equals(current.sectionPath)) {
                last.appendRaw("\n\n" + current.text());
            } else {
                parentTexts.add(current);
            }
        } else if (current.length() > 0) {
            parentTexts.add(current);
        }
    }

    /** 内部使用的"段落"包装: 文本 + 是否为原子结构(code/table) + 该段所属 section_path。 */
    private record BlockParagraph(String text, boolean atomic, List<String> sectionPath) {}

    /** parent 累积器(可变)。Q3-B: 记录 parent 文本 + 它的 section_path(取首段所属 heading)。 */
    private static final class ParentAccumulator {
        private final StringBuilder sb = new StringBuilder();
        private List<String> sectionPath = List.of();

        void append(String para) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(para);
        }

        /** tail flush 专用: 原样追加(已含分隔符)。 */
        void appendRaw(String s) {
            sb.append(s);
        }

        int length() {
            return sb.length();
        }

        String text() {
            return sb.toString();
        }
    }

    /**
     * 模式 1 带章节的切片产物。
     *
     * @param text chunk 正文
     * @param sectionPath 该 chunk 所属 heading 路径栈(空 list = 无 heading 上下文)
     */
    public record SectionedFlatChunk(String text, List<String> sectionPath) {}

    /**
     * 模式 2 带章节的 Parent-Child 切片产物。
     *
     * @param childText child 正文(入 Milvus 索引用)
     * @param parentText parent 全文(回链喂 LLM 用)
     * @param parentIndex parent 在原文中的序号(0-based, 仅用于日志)
     * @param sectionPath child 所属 heading 路径栈(取自 parent)
     */
    public record SectionedParentChildChunk(
            String childText, String parentText, int parentIndex, List<String> sectionPath) {}

    /**
     * Parent-Child 切片产物(向后兼容老 API: 不带 sectionPath)。
     *
     * @param childText child 正文(入 Milvus 索引用)
     * @param parentText parent 全文(回链喂 LLM 用)
     * @param parentIndex parent 在原文中的序号(0-based, 仅用于日志, 落库用 parent_chunk_id 关联)
     */
    public record ParentChildChunk(String childText, String parentText, int parentIndex) {}
}
