package tn.steg.backend.iam.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
// The Spring Data repository (not the domain port) so the reset below can flush
// and delete; the production code under test only uses the port.
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 2 — the first-run admin bootstrap (production). Every rule from the
 * requirement is pinned against real Postgres, in the TEST profile where the
 * demo seeder is OFF by default (which is exactly the fourth requirement:
 * the bootstrap must work — and create nothing else — without any seeder).
 *
 * <p>The runner wiring (reads {@code STEG_BOOTSTRAP_ADMIN_EMAIL}/
 * {@code _PASSWORD} from the environment at boot) is exercised by running the
 * package-visible core with the same values the environment would supply; the
 * property gate itself is a two-line Environment read pinned by the
 * not-configured no-op test.
 *
 * <p>The marker property above gives this class its OWN Spring context (and
 * therefore its own Postgres container) instead of sharing the suite-wide
 * cached context: the bootstrap gate is global by definition ("no ADMIN
 * exists"), so it can only be observed on a database that holds nothing but
 * the V27 seed rows. Sharing the suite database would make the result depend
 * on which test class happened to run first. {@code @Transactional} then
 * rolls each test method back, so every method starts from that same
 * post-migration state regardless of execution order.
 */
@SpringBootTest(properties = "steg.test.context-isolated=first-run-admin-bootstrap")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("First-run admin bootstrap — one operator Admin, environment-supplied, no demo data")
class FirstRunAdminBootstrapTest {

    @Autowired private FirstRunAdminBootstrap bootstrap;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private long nonQaUserCount() {
        return userRepository.findAll().stream()
                .filter(u -> !tn.steg.backend.iam.domain.QaSeedAccounts.IDS.contains(u.getId()))
                .count();
    }

    /** Post-migration state on this dedicated database: only the three V27 QA rows. */
    @org.junit.jupiter.api.BeforeEach
    void postMigrationStateOnly() {
        assertThat(nonQaUserCount()).isZero();
    }

    @Test
    @DisplayName("creates exactly one login-capable Admin with a forced password change — and nothing else")
    void createsExactlyOneAdminWithForcedPasswordChange() {
        long before = nonQaUserCount();
        String email = "bootstrap-admin-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@steg.tn";

        bootstrap.bootstrap(email, "Str0ng!Bootstrap#2026");

        List<User> created = userRepository.findAll().stream()
                .filter(u -> email.equals(u.getEmail()))
                .toList();
        assertThat(created).hasSize(1);
        User admin = created.get(0);
        assertThat(admin.getAssignedRoles()).extracting(r -> r.getCode()).containsExactly("ADMIN");
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.getEnabled()).isTrue();
        assertThat(admin.getMustChangePassword()).isTrue();
        // BCrypt hash, never the plaintext.
        assertThat(admin.getPasswordHash()).startsWith("$2").doesNotContain("Str0ng");
        // No demo data, nothing else: exactly one new row beyond the V27 seeds.
        assertThat(nonQaUserCount()).isEqualTo(before + 1);

        // A second run is a strict no-op: the admin exists.
        bootstrap.bootstrap(email, "Another!Different#1");
        assertThat(userRepository.findAll().stream().filter(u -> email.equals(u.getEmail())).count()).isEqualTo(1);
        assertThat(nonQaUserCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("is a no-op when a login-capable ADMIN already exists — even with different credentials")
    void isNoOpWhenAnAdminExists() {
        long before = nonQaUserCount();
        // The V27 QA accounts are deliberately excluded from the gate, but a
        // REAL operator admin suppresses the bootstrap. Create one first.
        bootstrap.bootstrap("first-operator-" + java.util.UUID.randomUUID().toString().substring(0, 6) + "@steg.tn",
                "Str0ng!First#2026");
        long afterFirst = nonQaUserCount();

        bootstrap.bootstrap("second-operator-" + java.util.UUID.randomUUID().toString().substring(0, 6) + "@steg.tn",
                "An0ther!Strong#9");

        assertThat(nonQaUserCount()).isEqualTo(afterFirst);
        assertThat(afterFirst).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("refuses weak passwords (short / missing classes) and creates nothing")
    void refusesWeakPasswords() {
        long before = nonQaUserCount();
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 6);

        assertThatThrownBy(() -> bootstrap.bootstrap("weak1-" + suffix + "@steg.tn", "Sh0rt!x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too weak");
        assertThatThrownBy(() -> bootstrap.bootstrap("weak2-" + suffix + "@steg.tn", "alllowercase1!x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too weak");
        assertThatThrownBy(() -> bootstrap.bootstrap("weak3-" + suffix + "@steg.tn", "NoSymbols12345"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too weak");
        assertThat(nonQaUserCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("not configured → strict no-op; the V27 QA accounts never count as an existing admin")
    void unconfiguredIsNoOpAndQaAccountsNeverSuppressCreation() {
        long before = nonQaUserCount();
        // Not configured (the default state): nothing happens at all.
        bootstrap.bootstrap(null, null);
        bootstrap.bootstrap("", "");
        assertThat(nonQaUserCount()).isEqualTo(before);

        // Half-configured → loud refusal, not a silent skip.
        assertThatThrownBy(() -> bootstrap.bootstrap("only-email@steg.tn", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOTH");

        // The V27 QA admin exists, is ACTIVE and enabled in this (non-prod)
        // environment — yet the bootstrap still fires: seed artifacts never
        // count as an operator account.
        bootstrap.bootstrap("operator-despite-qa-" + java.util.UUID.randomUUID().toString().substring(0, 6) + "@steg.tn",
                "Str0ng!Desp1te#QA");
        assertThat(nonQaUserCount()).isEqualTo(before + 1);
    }
}
