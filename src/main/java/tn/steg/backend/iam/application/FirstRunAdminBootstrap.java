package tn.steg.backend.iam.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.audit.domain.model.AuditSource;
import tn.steg.backend.common.domain.util.NullSafe;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.iam.domain.service.PasswordPolicy;

/**
 * First-run admin bootstrap for production (Task 2 of the hardening sweep).
 *
 * <p>Activated ONLY when BOTH {@code steg.bootstrap.admin.email} and
 * {@code steg.bootstrap.admin.password} are configured
 * ({@code STEG_BOOTSTRAP_ADMIN_EMAIL} / {@code STEG_BOOTSTRAP_ADMIN_PASSWORD}
 * in the environment). It then runs at every boot but has an effect exactly
 * once per database: when NO ADMIN user exists, it creates exactly ONE Admin
 * account with {@code mustChangePassword=true} — no demo data, nothing else.
 * When an ADMIN already exists it is a strict no-op, so the bootstrap can
 * safely stay in the environment configuration.
 *
 * <p>Hard rules, each pinned by {@code FirstRunAdminBootstrapTest}:
 * <ul>
 *   <li>the password comes from the environment, is BCrypt-hashed and is
 *       NEVER logged, audited or returned;</li>
 *   <li>a weak password (failing {@link PasswordPolicy#hasRequiredCharacterClasses}
 *       or shorter than 12 chars) REFUSES the boot — a misconfigured
 *       production start with a guessable admin password must not proceed;</li>
 *   <li>the demo seeder is irrelevant here: this component never depends on
 *       it and never seeds anything but the one admin row.</li>
 * </ul>
 *
 * <p>Timing: an {@link ApplicationRunner} runs after the context is ready.
 * For a first-run bootstrap that is the correct order of magnitude — before
 * it runs, NO admin exists anyway, so there is no window in which an
 * existing privileged account is unguarded (the QA-seed accounts are handled
 * earlier, by {@code ProdQaSeedAccountCallback} during Flyway migration).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FirstRunAdminBootstrap implements ApplicationRunner {

    static final String EMAIL_PROPERTY = "steg.bootstrap.admin.email";
    static final String PASSWORD_PROPERTY = "steg.bootstrap.admin.password";
    static final int MIN_PASSWORD_LENGTH = 12;

    private final Environment environment;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        bootstrap(environment.getProperty(EMAIL_PROPERTY, ""), environment.getProperty(PASSWORD_PROPERTY, ""));
    }

    /**
     * Core logic, package-visible so tests can exercise every rule against a
     * real Postgres without one Spring context per configuration.
     */
    void bootstrap(String email, String password) {
        if ((email == null || email.isBlank()) && (password == null || password.isBlank())) {
            return; // Not configured — nothing to do (the default state).
        }
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "First-run admin bootstrap requires BOTH " + EMAIL_PROPERTY + " and " + PASSWORD_PROPERTY);
        }
        String trimmedEmail = email.trim();
        if (!trimmedEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalStateException("First-run admin bootstrap: the configured email is not a valid address");
        }
        if (password.length() < MIN_PASSWORD_LENGTH
                || !PasswordPolicy.hasRequiredCharacterClasses(password)) {
            throw new IllegalStateException(
                    "First-run admin bootstrap refused: the configured password is too weak (needs at least "
                            + MIN_PASSWORD_LENGTH + " characters with uppercase, lowercase, digit and symbol)");
        }
        if (countAdmins() > 0) {
            log.info("First-run admin bootstrap skipped: an ADMIN account already exists");
            return;
        }

        Role adminRole = roleRepository.findByCode("ADMIN")
                .orElseThrow(() -> new IllegalStateException("First-run admin bootstrap: ADMIN role is missing — migrate first"));
        User admin = new User(trimmedEmail, passwordEncoder.encode(password), UserStatus.ACTIVE);
        admin.setEnabled(true);
        admin.setMustChangePassword(true);
        admin.getAssignedRoles().add(adminRole);
        admin = userRepository.save(admin);

        auditService.log("FIRST_RUN_ADMIN_CREATED", "User", admin.getId(),
                null, NullSafe.mapOf("mustChangePassword", true, "role", "ADMIN"),
                null, null, null, null, AuditSource.SYSTEM);
        // Never the password; per the PII log rule, not even the email.
        log.info("First-run admin bootstrap: created the initial ADMIN account (id={}) with a forced password change", admin.getId());
    }

    private long countAdmins() {
        // "An ADMIN exists" means a LOGIN-CAPABLE operator account. The V27
        // QA seed accounts are excluded by id: they are seed artifacts (and
        // disabled in production by the Flyway callback), never operators —
        // they must not suppress the first-run creation.
        return userRepository.findDistinctByAssignedRoles_Code("ADMIN").stream()
                .filter(u -> Boolean.TRUE.equals(u.getEnabled()) && u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> !tn.steg.backend.iam.domain.QaSeedAccounts.IDS.contains(u.getId()))
                .count();
    }
}
