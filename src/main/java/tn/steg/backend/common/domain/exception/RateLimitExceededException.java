package tn.steg.backend.common.domain.exception;

/**
 * Thrown when a request exceeds the configured rate limit for its endpoint.
 *
 * <p>Mapped by {@code GlobalExceptionHandler} to HTTP 429 with a
 * {@code Retry-After} header so conformant clients back off without retrying
 * the full payload.
 */
public class RateLimitExceededException extends RuntimeException {

    private final String bucketName;
    private final int limit;
    private final int windowSeconds;

    public RateLimitExceededException(String message, String bucketName, int limit, int windowSeconds) {
        super(message);
        this.bucketName = bucketName;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public String getBucketName() {
        return bucketName;
    }

    public int getLimit() {
        return limit;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }
}