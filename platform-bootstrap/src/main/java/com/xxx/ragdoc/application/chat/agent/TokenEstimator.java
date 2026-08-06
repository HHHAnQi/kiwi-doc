package com.xxx.ragdoc.application.chat.agent;

/**
 * PR-6b.2 / EMS-PR6 §8.4: Token 估算器 (保守, 不引入 LLM tokenizer 依赖)。
 *
 * <p>对 CJK 字符按 <b>1 char ≈ 1 token</b> 估算 (中文 / 日文 / 韩文 Bpe 多为单字符单 token);
 * 非 CJK 按 <b>4 char ≈ 1 token</b> (英文 BPE 平均 1 token ≈ 4 chars)。
 *
 * <p>关键不变量: 保守 + 显式标注 ESTIMATED_NOT_PRECISE; 不允许在 metadata 里声称为精确 token 数。
 * 这是对 Revision §4.4 的实施: 单纯 len/4 会显著低估中文, 容易让上下文真超预算。
 */
public final class TokenEstimator {

    /** 标记 metadata 中本估算的精确度等级。 */
    public static final String PRECISION_LABEL = "ESTIMATED_NOT_PRECISE";

    private TokenEstimator() {}

    public static int estimate(String content) {
        if (content == null || content.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (isCjk(c)) cjk++;
            else other++;
        }
        // CJK 保守 1:1, 非 CJK 保守 ceil(n/4)
        return cjk + (int) Math.ceil(other / 4.0);
    }

    private static boolean isCjk(char c) {
        // CJK Unified + Ext A + Hiragana + Katakana + Hangul 是最常见的中文/日文/韩文段
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK Unified Ideographs
                || (c >= 0x3040 && c <= 0x30FF) // Hiragana + Katakana
                || (c >= 0xAC00 && c <= 0xD7AF) // Hangul Syllables
                || (c >= 0x3400 && c <= 0x4DBF); // CJK Ext A
    }
}
