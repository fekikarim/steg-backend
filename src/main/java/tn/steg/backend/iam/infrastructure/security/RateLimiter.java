package tn.steg.backend.iam.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tn.steg.backend.iam.application.port.out.RateLimiterPort;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RateLimiter implements RateLimiterPort {

    private final ConcurrentHashMap<String, RateLimitEntry> attempts = new ConcurrentHashMap<>();

    private static final int MAX_ATTEMPTS = 10;
    private static final long WINDOW_MS = 60_000; // 1 minute

    public boolean isRateLimited(String key) {
        RateLimitEntry entry = attempts.compute(key, (k, existing) -> {
            long now = System.currentTimeMillis();
            if (existing == null || now - existing.windowStart > WINDOW_MS) {
                return new RateLimitEntry(now, 1);
            }
            existing.count++;
            return existing;
        });

        if (entry.count > MAX_ATTEMPTS) {
            log.warn("Rate limit exceeded for key: {}", key);
            return true;
        }
        return false;
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    private static class RateLimitEntry {
        long windowStart;
        int count;

        RateLimitEntry(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
