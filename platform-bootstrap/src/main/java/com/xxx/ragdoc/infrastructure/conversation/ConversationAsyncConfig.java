package com.xxx.ragdoc.infrastructure.conversation;

import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 多轮对话异步执行池, ADR-0011 §9.1。
 *
 * <p>{@code @ConditionalOnProperty rag.conversation.enabled=true} 启用本配置 + @EnableAsync, 否则
 * ChatService 根本不调 HistoryCompressor (多轮 disabled), 不需要 async pool。
 *
 * <h3>线程池 5 决策 (ADR-0011 §9.1)</h3>
 *
 * <ul>
 *   <li>corePool = maxPool = 2: 不弹性扩。压缩是后台任务, 扩到 8 线程只会把 LLM API 打爆 (rate limit)
 *   <li>queueCapacity = 100: 100 个等待任务上限, 超过即丢 (debounce 保证正常情况下队列不会真满)
 *   <li>DiscardPolicy: 拒绝策略 = 静默丢。用户 chat 完全感知不到压缩失败, 下次 turn 还会再触发
 *   <li>跟 Spring 默认 taskExecutor 隔离 (用 bean name "historyCompressorPool")
 *   <li>threadNamePrefix "conv-compress-": 线程 dump 一目了然
 * </ul>
 *
 * <p>{@code @EnableAsync} 必须加 (Spring 默认 fallback proxy 时也要), 否则 {@code @Async} 注解无效。
 * 用一个 dedicated @Configuration 加 @EnableAsync 比加在 Application 类更内聚。
 *
 * @author Phase 1 / C6 (ADR-0011)
 */
@Slf4j
@Configuration
@EnableAsync
@ConditionalOnProperty(
        prefix = "rag.conversation",
        name = "enabled",
        havingValue = "true")
public class ConversationAsyncConfig {

    @Bean(name = "historyCompressorPool")
    public ThreadPoolTaskExecutor historyCompressorPool() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("conv-compress-");
        // 队列满 → silent drop (不阻塞调用线程, 不抛异常); 下次 save 还会再触发
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        exec.initialize();
        log.info(
                "historyCompressorPool enabled: core=2, max=2, queue=100, rejectPolicy=Discard");
        return exec;
    }
}
