package com.xxx.ragdoc.interfaces.rest.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * trace_id 贯穿过滤器:
 * <ul>
 *   <li>从入站 header {@code X-Trace-Id} 透传或生成 8 位短码</li>
 *   <li>写 MDC,让所有日志自动含 trace_id</li>
 *   <li>响应头 {@code X-Trace-Id} 暴露给客户端</li>
 * </ul>
 *
 * 见 docs/architecture/observability.md §2。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_KEY = "trace_id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(TRACE_HEADER));
        if (traceId == null) {
            traceId = generate();
        }
        try {
            MDC.put(MDC_TRACE_KEY, traceId);
            response.setHeader(TRACE_HEADER, traceId);
            // 允许前端读
            response.addHeader("Access-Control-Expose-Headers", TRACE_HEADER);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_KEY);
        }
    }

    /**
     * 仅允许字母数字短码,防日志注入。
     */
    private static String sanitize(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (!trimmed.matches("^[A-Za-z0-9_-]{1,64}$")) {
            return null;
        }
        return trimmed;
    }

    /**
     * 生成 8 位短码(便于人眼识别与日志可读);UUID 提供随机性。
     */
    private static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
