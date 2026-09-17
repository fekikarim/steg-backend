package tn.steg.backend.common.infrastructure.seed;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;

/**
 * E0.4/E0.5 — integration-profile demo seeder.
 *
 * <p>Runs ONLY on {@code integration} profile (never {@code clean}/prod/test).
 * Demo data lives here — never in Flyway migrations. All people, CINs, emails
 * and phone numbers are fictional and marked as such. Idempotent: safe to
 * re-run; existing emails are skipped.
 *
 * <p>Seeds one working account per role (password from
 * {@code STEG_SEED_DEMO_PASSWORD}, default {@code Demo#2026} DEV-ONLY):
 * CANDIDATE, INTERN, SUPERVISOR, HR, FINANCE, DIRECTOR, ADMIN.
 */
@Component
@Profile("integration")
@ConditionalOnProperty(name = "steg.seed.demo-enabled", havingValue = "true", matchIfMissing = true)
public class IntegrationDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IntegrationDemoSeeder.class);

    /** Fictional demo accounts — clearly namespaced, never real STEG personnel. */
    private static final Map<String, String> DEMO_ACCOUNTS = new LinkedHashMap<>();

    static {
        DEMO_ACCOUNTS.put("candidate.demo@demo.steg.tn", "CANDIDATE");
        DEMO_ACCOUNTS.put("intern.demo@demo.steg.tn", "INTERN");
        DEMO_ACCOUNTS.put("supervisor.demo@demo.steg.tn", "SUPERVISOR");
        DEMO_ACCOUNTS.put("hr.demo@demo.steg.tn", "HR");
        DEMO_ACCOUNTS.put("finance.demo@demo.steg.tn", "FINANCE");
        DEMO_ACCOUNTS.put("director.demo@demo.steg.tn", "DIRECTOR");
        DEMO_ACCOUNTS.put("admin.demo@demo.steg.tn", "ADMIN");
    }

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassword;

    public IntegrationDemoSeeder(
            UserRepository users,
            RoleRepository roles,
            PasswordEncoder passwordEncoder,
            @Value("${steg.seed.demo-password:Demo#2026}") String demoPassword) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;
        for (Map.Entry<String, String> entry : DEMO_ACCOUNTS.entrySet()) {
            String email = entry.getKey();
            String roleCode = entry.getValue();
            if (users.existsByEmail(email)) {
                continue;
            }
            Role role = roles.findByCode(roleCode)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing role '" + roleCode + "' — Flyway V15 must run before the demo seeder."));
            User user = new User(email, passwordEncoder.encode(demoPassword), UserStatus.ACTIVE);
            user.setEnabled(true);
            user.setPreferredLocale("fr");
            user.setEmailNotificationsEnabled(false);
            user.getAssignedRoles().add(role);
            users.save(user);
            created++;
            log.info("Seeded fictional demo account {} with role {} (DEMO DATA — NOT FOR PRODUCTION)", email, roleCode);
        }
        log.info("Integration demo seeding complete: {} created, {} total (DEMO DATA — fictional accounts only)",
                created, DEMO_ACCOUNTS.size());
    }
}
