package tn.steg.backend.iam.domain.exception;

import tn.steg.backend.common.domain.exception.BusinessRuleException;

public class InvalidRefreshTokenException extends BusinessRuleException {

    public InvalidRefreshTokenException(String message) {
        super("INVALID_REFRESH_TOKEN", message);
    }

    public static InvalidRefreshTokenException expired() {
        return new InvalidRefreshTokenException("The refresh token has expired. Please log in again.");
    }

    public static InvalidRefreshTokenException revoked() {
        return new InvalidRefreshTokenException("The refresh token has been revoked. Please log in again.");
    }

    public static InvalidRefreshTokenException notFound() {
        return new InvalidRefreshTokenException("The refresh token is invalid or does not exist.");
    }
}
