package tn.steg.backend.iam.domain.exception;

import tn.steg.backend.common.domain.exception.BusinessRuleException;

public class AccountLockedException extends BusinessRuleException {

    private final java.time.Instant lockedUntil;

    public AccountLockedException(java.time.Instant lockedUntil) {
        super("ACCOUNT_LOCKED", "This account has been locked due to too many failed login attempts. Try again after " + lockedUntil);
        this.lockedUntil = lockedUntil;
    }

    public java.time.Instant getLockedUntil() {
        return lockedUntil;
    }
}
