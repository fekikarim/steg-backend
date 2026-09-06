package tn.steg.backend.common.infrastructure.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tn.steg.backend.common.domain.annotation.RateLimited;
import tn.steg.backend.common.domain.exception.RateLimitExceededException;
import tn.steg.backend.common.domain.model.UserPrincipal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory sliding-window rate limiter enforcing {@link RateLimited} on
 * resource-intensive endpoints (file uploads, AI inference).
 *
 * <h2>Operational contract</h2>
 * <ul>
 *   <li>A bucket is keyed by {@code <endpoint>|<principal>}; the authenticated
 *       {@link UserPrincipal} guarantees that quota is never shared between
 *       users and that a single abusive caller cannot starve others.</li>
 *   <li>Requests over the limit within the window throw
 *       {@link RateLimitExceededException} (HTTP 429 + {@code Retry-After})
 *       <em>before</em> the target method runs, so the expensive work (LLM
 *       call, disk write) is never triggered.</li>
 *   <li>Burstable callers get a fair, bounded answer: legitimate authorized
 *       requests remain usable, abusive request rates are rejected safely.</li>
 *   <li>Memory is bounded: the bucket map is capped and expired timestamps are
 *       pruned on every access; the limiter degrades to &quot;allow&quot; rather
 *       than rejecting traffic if the cap is reached.</li>
 * </ul>
 *
 * <h2>Known limitation (multi-instance)</h2>
 * <p>Counters are process-local. In a multi-replica deployment each instance
 * enforces the limit independently, so the effective cluster allowance is
 * {@code limit &times; replicas}. Horizontally-scaling deployments must back
 * this with a shared store (e.g. Redis); per-current-architecture single-instance
 * operation keeps the guarantee exact.
 */
@Aspect
@Component
@Slf4j
public class RateLimitingAspect {

    private static final int MAX_BUCKETS = 4096;

    private final ConcurrentMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Around("@annotation(rateLimited)")
    public Object enforce(ProceedingJoinPoint joinPoint, RateLimited rateLimited) throws Throwable {
        String bucketName = rateLimited.name().isBlank()
                ? joinPoint.getSignature().getDeclaringTypeName() + "#" + joinPoint.getSignature().getName()
                : rateLimited.name();
        String bucketKey = bucketName + "|" + resolvePrincipalKey();

        long now = System.currentTimeMillis();
        long windowMillis = rateLimited.windowSeconds() * 1000L;

        Deque<Long> bucket = windows.get(bucketKey);
        if (bucket == null) {
            if (windows.size() >= MAX_BUCKETS) {
                // Memory cap reached: allow rather than reject legitimate
                // traffic (availability over strictness under abnormal load).
                log.warn("Rate limiter bucket map is full ({} buckets); allowing request to {}", MAX_BUCKETS, bucketName);
                return joinPoint.proceed();
            }
            Deque<Long> created = new ArrayDeque<>();
            Deque<Long> existing = windows.putIfAbsent(bucketKey, created);
            bucket = existing != null ? existing : created;
        }

        // Prune expired timestamps; the deque stays bounded to the window.
        while (!bucket.isEmpty() && now - bucket.peekFirst() >= windowMillis) {
            bucket.pollFirst();
        }

        if (bucket.size() >= rateLimited.limit()) {
            throw new RateLimitExceededException(
                    "Too many requests on " + bucketName + " (limit " + rateLimited.limit()
                            + " per " + rateLimited.windowSeconds() + "s). Please retry later.",
                    bucketName, rateLimited.limit(), rateLimited.windowSeconds());
        }

        bucket.offerLast(now);

        return joinPoint.proceed();
    }

    private String resolvePrincipalKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal user) {
            return user.getId().toString();
        }
        // Anonymous fallback — the affected endpoints are authenticated in the
        // security layer, but the limiter still protects against unauthenticated
        // noise by keying on the client IP instead of collapsing to one bucket.
        return remoteAddress();
    }

    private String remoteAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        return "unknown";
    }
}