package tn.steg.backend.common.domain.exception;

/**
 * Thrown when a requested status transition is not permitted from the current state.
 * <p>
 * This is distinct from {@link BusinessRuleException}: an invalid transition is a
 * <em>conflict</em> (HTTP 409), not a generic business rule violation (HTTP 422).
 * The {@link tn.steg.backend.common.interfaces.rest.GlobalExceptionHandler} maps this
 * exception to {@code 409 Conflict}.
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final String currentState;
    private final String requestedTransition;

    public InvalidStateTransitionException(String currentState, String requestedTransition, String message) {
        super(message);
        this.currentState = currentState;
        this.requestedTransition = requestedTransition;
    }

    public InvalidStateTransitionException(String message) {
        this("UNKNOWN", "UNKNOWN", message);
    }

    public String getCurrentState() {
        return currentState;
    }

    public String getRequestedTransition() {
        return requestedTransition;
    }
}
