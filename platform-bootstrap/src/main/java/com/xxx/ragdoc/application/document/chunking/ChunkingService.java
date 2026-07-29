package com.xxx.ragdoc.application.document.chunking;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Token-based 文本切片器。
 *
 * <p>设计决策(架构师视角):
 *
 * <ul>
 *   <li>V1 stub 用的固定长度 char 切片太粗糙, V2 改为 token-based 更接近 LLM 真实 token 边界
 *   <li>用 BGE-M3 tokenizer 太重(需引入 tokenizers 库), V2 简化用"字符数近似 token": 中文 1 字 ≈ 1-2 token, 英文 4 字符 ≈
 *       1 token, 平均 ratio 约 TokensPerChar=0.5
 *   <li>chunk_size=512 token → 实际按 800 字符切(近似); overlap=50 token → 80 字符
 *   <li>在句号/换行等边界切断, 避免把句子切两半
 * </ul>
 *
 * <p>V3 才上 Parent-Child / 语义切片; V2 务实优先(能跑通召回就行)。
 */
@Component
public class ChunkingService {

    /** 目标 chunk 长度(字符, 近似 512 token)。 */
    private static final int CHUNK_CHARS = 800;

    /** 重叠长度(字符, 近似 50 token overlap)。 */
    private static final int OVERLAP_CHARS = 80;

    /** 短于此值的尾部块丢弃(避免噪声)。 */
    private static final int MIN_CHUNK_CHARS = 50;

    /**
     * 把全文切成带重叠的 chunks。
     *
     * @param fullText 文档解析后的全文
     * @return chunk 内容列表(顺序保证), 每条对应一个 chunk
     */
    public List<String> chunk(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return List.of();
        }

        List<String> rawChunks = new ArrayList<>();
        int step = CHUNK_CHARS - OVERLAP_CHARS; // 步长 = chunk - overlap
        int len = fullText.length();
        int pos = 0;

        while (pos < len) {
            int end = Math.min(pos + CHUNK_CHARS, len);
            String chunk = fullText.substring(pos, end);

            // 边界优化: 如果还能往后挪到最近的换行/句号, 切在那
            int adjustedEnd = findBreakPoint(fullText, pos, end);
            if (adjustedEnd > pos) {
                chunk = fullText.substring(pos, adjustedEnd);
            }

            rawChunks.add(chunk);
            // 下一个起点
            int nextPos = adjustedEnd > pos ? adjustedEnd - OVERLAP_CHARS : pos + step;
            if (nextPos <= pos) nextPos = pos + step; // 防死循环
            pos = nextPos;
        }

        // 丢弃过短尾部块
        return rawChunks.stream().filter(c -> c.trim().length() >= MIN_CHUNK_CHARS).toList();
    }

    /** 在 [start, end] 范围内找一个最近的"自然边界"(换行 / 句号 / 问号)。 找不到就返回 end(强制硬切)。 */
    private static int findBreakPoint(String text, int start, int end) {
        // 向前找最近的换行符; 退而求其次找中文句号"."
        for (int i = end - 1; i > start + CHUNK_CHARS / 2; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i + 1;
            }
        }
        for (int i = end - 1; i > start + CHUNK_CHARS / 2; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '；' || c == '；' || c == '.') {
                return i + 1;
            }
        }
        return end;
    }
}
