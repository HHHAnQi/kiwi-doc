package com.xxx.ragdoc.application.chat.port;

import reactor.core.publisher.Flux;

/**
 * LLM 调用端口。V2-B 默认实现: {@code DashScopeChatClient}(通义千问 qwen-max)。
 *
 * <p>切换 LLM 只需新增 adapter (OpenAIChatClient / OllamaLocalClient 等), 配 base-url + api-key + model 即可,
 * application 层无感知。
 *
 * <p>设计原则: 端口方法返回纯字符串, 失败抛异常(由 ChatService 捕获转 LLM_DEGRADED)。
 */
public interface ChatClient {

    /**
     * 用已召回的上下文 + 用户问题, 让 LLM 生成答案(同步)。
     *
     * @param query 用户原始问题
     * @param context 已召回的 chunk 文本(已按相关性降序)
     * @return LLM 生成的答案(纯文本, 不含 prompt 痕迹)
     * @throws Exception LLM 调用失败(timeout, 4xx/5xx, 网络), 由调用方降级
     */
    String chat(String query, java.util.List<String> context) throws Exception;

    /**
     * V3 W1: 流式 chat(SSE 端点用)。返回 Flux, 每个元素为 LLM 增量生成的 token 片段。
     *
     * <p>调用方(ChatController 的 /chat/sse) 用 Flux.subscribe 把每个片段写入 SseEmitter, 实现 首 token &lt; 1.5s
     * 体感(对齐 ADR-0004 L3 SLA)。
     *
     * <p>注意: retrieve 链路异常被 Flux.error 传播, 调用方必须在 onError 处理(发 SSE error 事件 或关闭 emitter),
     * 否则会出现客户端长挂。
     *
     * @return Flux of incremental token chunks
     */
    Flux<String> chatStream(String query, java.util.List<String> context);
}
