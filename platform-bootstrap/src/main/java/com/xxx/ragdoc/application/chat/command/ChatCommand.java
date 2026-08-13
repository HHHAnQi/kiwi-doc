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
 * @param conversationId 可选 (Phase 1 / PR-2): 多轮对话 ID。null/blank = stateless 老路径。 PR-2 中由
 *     Orchestrator/Classic Pipeline 透传给 ChatService, 不进 Pipeline 业务字段集 (非 docId 类过滤条件)。
 */
public record ChatCommand(
        String query,
        Long docId,
        Integer topK,
        String source,
        String version,
        String language,
        String conversationId) {
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
        conversationId =
                (conversationId == null || conversationId.isBlank()) ? null : conversationId.trim();
    }

    /** 向后兼容老调用方(无元数据过滤, 6 字段老版本)。 */
    public ChatCommand(String query, Long docId, Integer topK) {
        this(query, docId, topK, null, null, null, null);
    }

    /** PR-1/2 之前 6 字段构造器 (跑老测试 / RetrieveServiceTest mock)。 */
    public ChatCommand(
            String query,
            Long docId,
            Integer topK,
            String source,
            String version,
            String language) {
        this(query, docId, topK, source, version, language, null);
    }

    /**
     * Phase 1 / C4 (ADR-0011 §7): 返回 query 被替换, 其他字段保持不变的副本。
     *
     * <p>用于多轮对话: ChatService 把 userQuery 经 QueryContextualizer 改写成 standalone query 后, 用本方法构造新 cmd
     * 喂 {@code RetrieveService.retrieve} (retrieve 用 standalone query), 但 cmd 其他字段
     * (docId/topK/source/version/language) 全保留。
     *
     * <p>注意: 实际原 user query 从 ChatService 入参 cmd.query 或 finalRetrieveQuery 局部变量都拿得到, 这是
     * retrieve-dedicated 的副本, 不污染原 cmd (后续 LLM prompt 仍用原 user query)。
     */
    public ChatCommand withQuery(String newQuery) {
        return new ChatCommand(newQuery, docId, topK, source, version, language, conversationId);
    }
}
