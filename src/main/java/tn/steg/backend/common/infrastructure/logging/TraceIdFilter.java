package tn.steg.backend.common.infrastructure.logging;

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
 * Phase A0 correlation id, promoted to first-class tracing in A13.
 *
 * Every inbound request receives a single trace id, taken from the client
 * supplied {@code X-Trace-Id} header when present (service-to-service
 * correlation) or generated otherwise. It is placed in the SLF4J MDC under
 * {@code traceId} so every log line emitted during the request — including
 * security-layer rejections — carries the same correlation value, and is
 * echoed back on the {@code X-Trace-Id} response header.
 *
 * Registered at the highest precedence so it runs BEFORE the Spring Security
 * filter chain; anonymous rejections must still be correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_KEY = "traceId";
    public static final String REQUEST_ATTR = TraceIdFilter.class.getName() + ".TRACE_ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        request.setAttribute(REQUEST_ATTR, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(MDC_TRACE_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_KEY);
        }
    }
}