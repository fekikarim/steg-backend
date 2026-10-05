package tn.steg.backend.common.domain.exception;

/**
 * HTTP 409 Conflict: the request is syntactically valid but the current state
 * of the resource forbids it (AGENTS.md §4 error model — e.g. deleting a
 * supervisor who still has assigned candidates, or assigning a candidate whose
 * application is not approved).
 *
 * <p>Distinct from {@link BusinessRuleException} (422: the request itself
 * violates a business rule on the payload) and from
 * {@link InvalidStateTransitionException} (409: state-machine transition
 * refused, always with the {@code INVALID_STATE_TRANSITION} code).
 */
public class ConflictException extends RuntimeException {

    private final String errorCode;

    public ConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
