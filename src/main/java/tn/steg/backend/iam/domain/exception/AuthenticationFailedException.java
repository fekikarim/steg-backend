package tn.steg.backend.iam.domain.exception;

import tn.steg.backend.common.domain.exception.BusinessRuleException;

public class AuthenticationFailedException extends BusinessRuleException {

    public AuthenticationFailedException(String message) {
        super("AUTHENTICATION_FAILED", message);
    }

    public static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException("Invalid email or password.");
    }
}
