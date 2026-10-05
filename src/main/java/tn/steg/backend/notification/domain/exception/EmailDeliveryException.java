package tn.steg.backend.notification.domain.exception;

/**
 * Signals that an email delivery attempt failed (transport error, timeout,
 * interruption, or missing provider configuration).
 *
 * <p>Lives in the domain layer so the application layer
 * ({@code NotificationService}) can reference it without depending on the
 * infrastructure mail implementation (Clean Architecture boundary).
 * Failures are retryable: callers mark the delivery FAILED with backoff and
 * the sweep retries asynchronously — business state never rolls back.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
