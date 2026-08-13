package com.xxx.ragdoc.application.chat.conversation.port;

import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import java.util.List;

/**
 * QueryContextualizer 端口 (ADR-0011 §6.2 / Phase 1 / C3)。
 *
 * <p>把后续问题改写为 standalone query (LlamaIndex condense_question 流程):
 *
 * <ul>
 *   <li>"那个配置对哪些协议生效?" + history → "延迟连接 connections 对 Dubbo/SOAP/HTTP 协议的生效范围"
 *   <li>history 空 → 直接返原 query, 不调 LLM
 * </ul>
 *
 * <p>实现侧约定:
 *
 * <ul>
 *   <li>异常或 LLM 失败 → 返回 {@code ContextualizeResult.originalFallback(currQuery)}, 不挂 chat
 *   <li>history 空直接跳过 LLM; 同步路径
 * </ul>
 *
 * <p>本 port 把 application.ChatService 与 infrastructure.QueryContextualizer 解耦, 让 ArchUnit
 * "application 不依赖 infrastructure" 规则不破。
 */
public interface QueryContextualizerPort {

    com.xxx.ragdoc.application.chat.conversation.ContextualizeResult contextualize(
            String currQuery, List<ConversationContext.Turn> recentTurns);
}
