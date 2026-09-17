package tn.steg.backend.common.application.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tn.steg.backend.common.domain.idempotency.IdempotencyKey;
import tn.steg.backend.common.domain.idempotency.IdempotencyKeyRepository;

/**
 * E1.6 — service-layer idempotency for critical mutations.
 *
 * <p>Clients send {@code X-Idempotency-Key} (any unique string per logical operation:
 * double-click, mobile retry, reconnect replay). The first completed response is
 * persisted and replayed byte-identically for the same (key, user, endpoint scope).
 * Failures are never cached, so a failed attempt can be retried with the same key.
 * Concurrent duplicates serialize on the unique constraint; the loser replays the
 * winner's stored response instead of re-executing.
 *
 * <p>The key is read from the current request (no controller/DTO/spec change —
 * the frozen OpenAPI contract is untouched). Calls without a key execute directly,
 * protected by the pre-existing state + unique-constraint guards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    public static final String HEADER = "X-Idempotency-Key";

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    /** Current request's key + scope, empty outside a request or without the header. */
    public static Optional<RequestKey> currentKey() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return Optional.empty();
            }
            HttpServletRequest request = attrs.getRequest();
            String key = request.getHeader(HEADER);
            if (key == null || key.isBlank()) {
                return Optional.empty();
            }
            String scope = request.getMethod() + " " + request.getRequestURI();
            return Optional.of(new RequestKey(key.strip(), scope));
        } catch (Exception ex) {
            log.warn("Idempotency key resolution degraded: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public record RequestKey(String key, String scope) {}

    /**
     * Runs {@code action} under {@code (key, user, scope)} protection.
     *
     * <p>Sequential duplicates (double-click, mobile retry, reconnect replay) replay the
     * stored response without re-executing. True-concurrent duplicates serialize on the
     * unique constraint; the loser fails safe with {@code DUPLICATE_REQUEST} (422 + typed
     * code) and the client retries with the same key to receive the winner's replay.
     * Failures are never cached. Joins the caller's transaction (no new transaction here).
     */
    public <T> T execute(UUID userId, RequestKey requestKey, Callable<T> action, Class<T> responseType) {
        if (requestKey == null) {
            return call(action);
        }
        String hash = sha256Hex(requestKey.key());
        Optional<IdempotencyKey> existing =
                repository.findByKeyHashAndUserIdAndScope(hash, userId, requestKey.scope());
        if (existing.isPresent()) {
            T replayed = deserialize(existing.get().getResponseBody(), responseType);
            if (replayed != null) {
                log.info("Idempotency replay: scope={} user={}", requestKey.scope(), userId);
                return replayed;
            }
            log.warn("Idempotency replay degraded (stored response unreadable); re-executing scope={}",
                    requestKey.scope());
        }
        T result = call(action);
        try {
            repository.save(new IdempotencyKey(hash, userId, requestKey.scope(), 200,
                    objectMapper.writeValueAsString(result)));
        } catch (DataIntegrityViolationException race) {
            // A concurrent duplicate completed first; this transaction must roll back
            // (the constraint violation poisons it), so fail safe and let the client
            // retry with the same key to receive the winner's replay.
            log.info("Idempotency race on scope={}; failing safe for retry", requestKey.scope());
            throw new tn.steg.backend.common.domain.exception.BusinessRuleException("DUPLICATE_REQUEST",
                    "A concurrent request with the same idempotency key is being processed. "
                            + "Retry with the same key to receive its result.");
        } catch (Exception ex) {
            // Caching must never break the mutation itself.
            log.warn("Idempotency store degraded for scope={}: {}", requestKey.scope(),
                    ex.getClass().getSimpleName());
        }
        return result;
    }

    private static <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Idempotent action failed", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            return null;
        }
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
