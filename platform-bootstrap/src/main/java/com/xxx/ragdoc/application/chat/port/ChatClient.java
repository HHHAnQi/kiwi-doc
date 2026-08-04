package com.xxx.ragdoc.application.chat.port;

import java.util.Optional;
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

    /**
     * Phase 3 / P3-5: 当前 client 绑定的 model 名 (用于 token counter tag, 区分 glm-4-plus / deepseek / qwen)。
     *
     * <p>实现侧固定返回 route 配置中的 model; 老 adapter / NoOp 返 "unknown"。调用方不应据此切换业务分支。
     */
    default String currentModel() {
        return "unknown";
    }

    /**
     * Phase 3 / P3-5: 上一次同步 {@link #chat} 调用返回的 token 使用量。SSE / 老 adapter 不实现 → 返 empty。
     *
     * <p>线程安全注意: 实现侧每个 client 实例仅服务一次同步调用序列 (Spring 单例 + 并发触发时, caller
     * 在 chat() 返回后立即取 lastUsage, 不应跨调用读取), 是 best-effort 观测, 不保证严格一致。
     * 实现侧用 ThreadLocal / volatile 都可, OpenAiCompatibleLlmClient 用 volatile lastUsage (last-write-wins)。
     *
     * <p>调用方应在 chat() 返回后立即调用本方法, 间隔越长丢失概率越大。
     */
    default Optional<TokenUsage> lastUsage() {
        return Optional.empty();
    }

    /**
     * Phase 3 / P3-5: OpenAI 兼容 LLM 响应 usage 块 (prompt_tokens / completion_tokens / total_tokens)。
     *
     * @param promptTokens 系统提示 + 上下文 + 用户问题 消耗的 token 数
     * @param completionTokens LLM 生成答案的 token 数
     * @param totalTokens prompt + completion 之和, 兼容 OpenAI 标准; 可由 prompt+completion 推算
     */
    record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

        /** 当响应 usage 块缺失 / 解析失败时, 返此占位表示"无 token 信息"。 */
        public static TokenUsage unknown() {
            return new TokenUsage(0, 0, 0);
        }
    }
}
