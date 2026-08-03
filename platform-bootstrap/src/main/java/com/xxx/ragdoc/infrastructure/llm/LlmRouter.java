package com.xxx.ragdoc.infrastructure.llm;

import com.xxx.ragdoc.application.chat.port.ChatClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Phase 1.B (2026-08-03): ChatClient 的 @Primary 实现 — 多路由 + 自动 fallback + CircuitBreaker。
 *
 * <p>工作流程:
 * <ol>
 *   <li>启动期从 {@link LlmRouteProperties} 解析 routes, 按 primary/fallback role 名找两个 Route
 *       分别 new {@link OpenAiCompatibleLlmClient}(不绑死 baseUrl）。DashScopeChatClient 保留兼容性
 *       但不再被 ChatService 直注(因为 LlmRouter @Primary 优先)。
 *   <li>{@link #chat} 调用: 直接走 primary, 异常 → fallback 接管。两档都熔断 → throw
 *       CallNotPermittedException (ChatService catch 后走 StateHint.LLM_DEGRADED 兜底, 与 baseline 行为一致)。
 *   <li>{@link #chatStream} 同理, 用 {@link CircuitBreakerOperator} 装饰 Flux。
 * </ol>
 *
 * <p>CircuitBreaker 配置在 application.yml 挂到 instance name=llm-primary / llm-fallback。
 *
 * <p>backward compat: 当 routes 为空 (老 .env 配置只有 LLM_BASE_URL/LLM_API_KEY/LLM_MODEL) 时,
 * 自动从 {@link LlmProperties} 兜底构造 1 个 primary route, fallback 缺省则不做 fallback。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class LlmRouter implements ChatClient {

    private final LlmRouteProperties routeProps;
    private final LlmProperties llmProps;
    private final com.xxx.ragdoc.application.chat.ChatMessages chatMessages;
    private final CircuitBreakerRegistry cbRegistry;

    private OpenAiCompatibleLlmClient primary;
    private OpenAiCompatibleLlmClient fallback;
    private CircuitBreaker primaryCb;
    private CircuitBreaker fallbackCb;

    @PostConstruct
    void init() {
        // 优先用 routes 配置; 没配 primary role 但 routes 非空时, 仍不抢, 让 LlmProperties 兜底构造 primary。
        // 否则会把 fallback route 抢占成 primary — 这是 backward-incompat 行为, 必须避免。
        LlmRouteProperties.Route primaryRoute = routeProps.findByRole(routeProps.getPrimaryRole());
        if (primaryRoute == null) {
            // 配置 sanity: 找到的"primary" route 是否有效; 无效则用 LlmProperties 兜底
            primaryRoute = legacyPrimaryFromLlmProps();
            log.info("llm.router.primary from LlmProperties (model={})", primaryRoute.getModel());
        }

        LlmRouteProperties.Route fallbackRoute = routeProps.findByRole(routeProps.getFallbackRole());
        // 配置 sanity: fallback 仅当 baseUrl + apiKey 都非空才启用,
        // 否则视为 "fallback 未配置"(高度 backward compat — 老 .env 不配 LLM_FALLBACK_* 时自动降级到无 fallback)。
        if (fallbackRoute != null
                && (isBlank(fallbackRoute.getBaseUrl()) || isBlank(fallbackRoute.getApiKey()))) {
            log.info("llm.router.fallback_skipped base_url or api_key empty, primary only");
            fallbackRoute = null;
        }

        this.primary = new OpenAiCompatibleLlmClient(primaryRoute, llmProps, chatMessages);
        this.primaryCb = cbRegistry.circuitBreaker("llm-primary");
        log.info("llm.router.primary initialized: route={}, model={}, cb-state={}",
                primary.getRouteName(), primary.getModel(), primaryCb.getState());

        if (fallbackRoute != null) {
            this.fallback = new OpenAiCompatibleLlmClient(fallbackRoute, llmProps, chatMessages);
            this.fallbackCb = cbRegistry.circuitBreaker("llm-fallback");
            log.info("llm.router.fallback initialized: route={}, model={}, cb-state={}",
                    fallback.getRouteName(), fallback.getModel(), fallbackCb.getState());
        } else {
            log.warn("llm.router.no_fallback — primary 失败时直接抛"
                    + " (Phase 1.B 推荐 .env 配 LLM_FALLBACK_API_KEY/BASE_URL/MODEL 启用 fallback)");
        }
    }

    /** 从 LlmProperties 老配置构造一个 primary Route。高度 backward compat — Phase 1.B 前的 .env 零改动。 */
    private LlmRouteProperties.Route legacyPrimaryFromLlmProps() {
        LlmRouteProperties.Route r = new LlmRouteProperties.Route();
        r.setName("primary");
        r.setBaseUrl(llmProps.getBaseUrl());
        r.setApiKey(llmProps.getApiKey());
        r.setModel(llmProps.getModel());
        r.setTimeoutMs(llmProps.getTimeoutMs());
        r.setMaxTokens(llmProps.getMaxTokens());
        r.setTemperature(llmProps.getTemperature());
        return r;
    }

    @Override
    public String chat(String query, List<String> context) throws Exception {
        try {
            return primaryCb.executeSupplier(() -> {
                try {
                    return primary.chat(query, context);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception primaryErr) {
            if (!isFallbackConfigured()) {
                log.warn("llm.router.primary_failed_no_fallback query_len={}", query.length());
                throw primaryErr;
            }
            log.warn("llm.router.primary_failed_to_fallback reason={}", rootCause(primaryErr));
            return fallbackCb.executeSupplier(() -> {
                try {
                    return fallback.chat(query, context);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Override
    public Flux<String> chatStream(String query, List<String> context) {
        return primary.chatStream(query, context)
                .transformDeferred(CircuitBreakerOperator.of(primaryCb))
                .onErrorResume(e -> {
                    if (!isFallbackConfigured()) {
                        log.warn("llm.router.stream_primary_failed_no_fallback query_len={}", query.length());
                        return Flux.error(e);
                    }
                    log.warn("llm.router.stream_primary_failed_to_fallback reason={}", rootCause(e));
                    return fallback.chatStream(query, context)
                            .transformDeferred(CircuitBreakerOperator.of(fallbackCb));
                });
    }

    private boolean isFallbackConfigured() {
        return fallback != null && fallbackCb != null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String rootCause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        String name = c.getClass().getSimpleName();
        String msg = c.getMessage();
        if (msg != null && msg.length() > 100) msg = msg.substring(0, 100);
        return name + ": " + msg;
    }
}
