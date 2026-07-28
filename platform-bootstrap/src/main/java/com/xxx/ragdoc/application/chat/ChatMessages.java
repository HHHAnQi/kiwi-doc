package com.xxx.ragdoc.application.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * chat 业务配置: 友好文案 + 阈值。 放在 application 层(非 infrastructure), 是因为它表达业务语义而非技术配置。 V2 真实问答接入后,
 * min-score-threshold 才真正生效。
 *
 * <p>见 docs/features/error-and-empty-states/spec.md §配置项。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class ChatMessages {

    /** V2: 召回 score 低于此阈值视为 NO_RECALL */
    private double minScoreThreshold = 0.3;

    private String emptyKbMessage = "知识库中还没有任何文档, 请先上传一份 PDF/Markdown/HTML 文档再提问。";

    private String noRecallMessage = "未在知识库中找到相关信息, 请尝试改写问题或上传更相关的文档。";

    /** V2+ 生效: LLM 调用失败时的兜底文案前缀 */
    private String llmDegradedMessage = "模型暂不可用, 请稍后重试。如有紧急问题, 请点反馈并提供 trace_id: ";
}
