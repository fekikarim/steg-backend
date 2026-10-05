package tn.steg.backend.iam.infrastructure.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Production timing guarantee for the V27 QA seed accounts (Task 1 of the
 * hardening sweep).
 *
 * <p>{@code V27__seed_backoffice_test_accounts} ships three staff accounts
 * with PUBLIC passwords documented in the repository
 * ({@code admin.steg@steg.tn / Admin#2026}, {@code supervisor.steg@steg.tn},
 * {@code finance.steg@steg.tn}). As an applied migration it cannot be edited,
 * and a new unconditional V53 migration was rejected on purpose: those
 * accounts are NOT QA-only — with the demo seeder restricted to the
 * {@code integration} profile they are also the ONLY working admin logins for
 * local development, and {@code V40} backfills supervision to the seeded
 * admin row. Disabling them everywhere would break local development and the
 * {@code MigrationChainIntegrationTest} backfill expectations for no
 * production gain.
 *
 * <p>This callback therefore runs <strong>in production only</strong>, on the
 * Flyway {@code AFTER_MIGRATE} event. Flyway migrates during Spring Boot's
 * datasource initialization — strictly BEFORE the servlet container starts
 * accepting connections — so the public passwords are already unusable at the
 * first accepted request (the previous {@code ApplicationRunner} guard ran
 * only after the server was up; that race is gone). Idempotent: only
 * currently login-capable rows are touched; every disable is audited
 * (SYSTEM source) directly through the migration connection, because the
 * JPA stack is not initialized yet at this point in boot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProdQaSeedAccountCallback implements Callback {

    /**
     * The fixed V27 seed ids — see {@code QaSeedAccounts} (domain constants).
     */
    static final String[] V27_QA_USER_IDS = tn.steg.backend.iam.domain.QaSeedAccounts.IDS.stream()
            .map(UUID::toString)
            .toArray(String[]::new);

    private final Environment environment;

    @Override
    public boolean supports(Event event, Context context) {
        return Event.AFTER_MIGRATE.equals(event);
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(V27_QA_USER_IDS.length, "?"));
        String select = "SELECT id FROM users WHERE id IN (" + placeholders + ") "
                + "AND enabled = TRUE AND status = 'ACTIVE'";
        String update = "UPDATE users SET status = 'INACTIVE', enabled = FALSE, "
                + "updated_at = now(), version = version + 1 "
                + "WHERE id = ? AND enabled = TRUE AND status = 'ACTIVE'";
        String audit = "INSERT INTO audit_logs (id, action, entity_type, entity_id, new_values, "
                + "created_at, updated_at, source) VALUES (gen_random_uuid(), "
                + "'PROD_QA_SEED_ACCOUNT_DISABLED', 'User', ?, ?::jsonb, now(), now(), 'SYSTEM')";

        int disabled = 0;
        // The connection is OWNED by Flyway: never close it here, or its
        // afterMigrate transaction fails with "Unable to commit / connection
        // has been closed" and the whole boot dies.
        Connection connection = context.getConnection();
        try {
            java.util.List<UUID> loginCapable = new java.util.ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(select)) {
                for (int i = 0; i < V27_QA_USER_IDS.length; i++) {
                    ps.setObject(i + 1, UUID.fromString(V27_QA_USER_IDS[i]));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        loginCapable.add((UUID) rs.getObject(1));
                    }
                }
            }
            for (UUID id : loginCapable) {
                try (PreparedStatement ps = connection.prepareStatement(update)) {
                    ps.setObject(1, id);
                    disabled += ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(audit)) {
                    ps.setObject(1, id);
                    ps.setString(2, "{\"status\":\"INACTIVE\",\"enabled\":false}");
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            // Fail the boot rather than start a production server whose QA
            // accounts may still be enabled (fail-closed by construction).
            throw new IllegalStateException("Prod QA-seed account disable failed — refusing to start", e);
        }
        if (disabled > 0) {
            // Counts only — never emails, never password material.
            log.warn("Production boot: disabled {} V27 QA seed account(s) whose passwords are public in the repository", disabled);
        }
    }

    @Override
    public String getCallbackName() {
        return "ProdQaSeedAccountDisable";
    }
}
