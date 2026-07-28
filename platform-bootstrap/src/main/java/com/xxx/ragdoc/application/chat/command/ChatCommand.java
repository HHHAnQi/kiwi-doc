package com.xxx.ragdoc.application.chat.command;

/**
 * Chat 用例入参。Controller 把 JSON 转 Command, Service 不感知 HTTP。
 *
 * @param query 用户问题, 1-500 字
 * @param docId 可选; 限定文档; 不传 = 跨全库
 * @param topK 可选, 默认 5, [1, 20]; V1 仅做参数校验, 不实际召回
 */
public record ChatCommand(String query, Long docId, Integer topK) {
    public ChatCommand {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (query.length() > 500) {
            throw new IllegalArgumentException("query 长度不能超过 500");
        }
        if (topK != null && (topK < 1 || topK > 20)) {
            throw new IllegalArgumentException("topK 必须在 [1, 20]");
        }
    }
}
