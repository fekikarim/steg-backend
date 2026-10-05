package tn.steg.backend.iam.domain.service;

/**
 * The single login-credential strength rule of the platform, shared by the
 * self-service password change and the first-run admin bootstrap. A password
 * must contain at least one character of each class: uppercase, lowercase,
 * digit, symbol. One rule, one place — never re-implemented per caller.
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static boolean hasRequiredCharacterClasses(String password) {
        return password != null
                && password.matches(".*[A-Z].*")
                && password.matches(".*[a-z].*")
                && password.matches(".*\\d.*")
                && password.matches(".*[^A-Za-z0-9].*");
    }
}
