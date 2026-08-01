package com.xxx.ragdoc.application.chat.command;

/**
 * Chat 用例入参。Controller 把 JSON 转 Command, Service 不感知 HTTP。
 *
 * @param query 用户问题, 1-500 字
 * @param docId 可选; 限定文档; 不传 = 跨全库
 * @param topK 可选, 默认 5, [1, 20]
 * @param source 可选; 限定来源组件(dubbo/nacos/seata/rocketmq/sentinel), 不传 = 不限
 * @param version 可选; 限定版本
 * @param language 可选; 限定语言(zh/en)
 */
public record ChatCommand(
        String query, Long docId, Integer topK, String source, String version, String language) {
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
        // 元数据过滤字段统一样式: blank → null(下游 isEmpty 判定走)
        source = (source == null || source.isBlank()) ? null : source.trim();
        version = (version == null || version.isBlank()) ? null : version.trim();
        language = (language == null || language.isBlank()) ? null : language.trim();
    }

    /** 向后兼容老调用方(无元数据过滤)。 */
    public ChatCommand(String query, Long docId, Integer topK) {
        this(query, docId, topK, null, null, null);
    }
}
