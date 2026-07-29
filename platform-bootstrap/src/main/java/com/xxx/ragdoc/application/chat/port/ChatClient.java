package com.xxx.ragdoc.application.chat.port;

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
     * 用已召回的上下文 + 用户问题, 让 LLM 生成答案。
     *
     * @param query 用户原始问题
     * @param context 已召回的 chunk 文本(已按相关性降序)
     * @return LLM 生成的答案(纯文本, 不含 prompt 痕迹)
     * @throws Exception LLM 调用失败(timeout, 4xx/5xx, 网络), 由调用方降级
     */
    String chat(String query, java.util.List<String> context) throws Exception;
}
