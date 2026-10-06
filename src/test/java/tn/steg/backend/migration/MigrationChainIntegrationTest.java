package tn.steg.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12b migration chain proofs (AGENTS.md §9.2/§11): Flyway drives real
 * Postgres containers directly (no Spring), so the exact production upgrade
 * path is proven — an empty database reaches the latest version, and a V33
 * database carrying representative legacy rows (obsolete HR role, legacy
 * application/internship statuses, an unsupervised internship) lands on the
 * explicit model with nothing legacy left.
 */
@Testcontainers
@DisplayName("S12b — migration chain to latest (Testcontainers, raw Flyway)")
class MigrationChainIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static Connection driver(PostgreSQLContainer<?> pg) throws Exception {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static Flyway flywayTo(PostgreSQLContainer<?> pg, String target) {
        var config = Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static void exec(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private static String queryString(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int queryInt(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static long queryLong(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ------------------------------------------------------------------
    // 1. Empty database -> latest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("empty database migrates cleanly to V56 with every slice table present")
    void emptyDatabaseMigratesCleanlyToLatest() throws Exception {
        var result = flywayTo(POSTGRES, null).migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(56);

        try (Connection c = driver(POSTGRES)) {
            // Latest version marker.
            assertThat(queryString(c,
                    "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1"))
                    .isEqualTo("56");
            // One table per post-V33 slice: drafts (V48), chatbot history (V49),
            // certificate versions (V46), validation runs/decisions (V45).
            for (String table : java.util.List.of("ai_task_drafts", "ai_chat_messages",
                    "certificate_versions", "validation_verification_runs", "validation_decisions",
                    "audit_logs", "candidates", "internships", "internship_applications",
                    "task_categories", "task_category_apply_batches", "task_category_apply_items")) {
                assertThat(queryInt(c,
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", table))
                        .as("table %s exists", table).isEqualTo(1);
            }
            // Slice columns: audit source (V47), soft delete + manager (V41/V44),
            // user-backed supervisor (V35), must-change-password (V37).
            assertThat(queryInt(c,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'audit_logs' AND column_name = 'source'"))
                    .isEqualTo(1);
            assertThat(queryInt(c,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'candidates' AND column_name IN ('deleted_at','managed_by_user_id')"))
                    .isEqualTo(2);
            assertThat(queryInt(c,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'internships' AND column_name = 'supervisor_user_id'"))
                    .isEqualTo(1);
            // Notifications catalogue key (V54, T01): nullable so every
            // pre-V54 row keeps working, present on the slice table itself.
            assertThat(queryInt(c,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'notifications' AND column_name = 'type'"))
                    .isEqualTo(1);
            // Student task categories (V55, T03): the link is nullable so
            // every pre-V55 task reads as unclassified.
            assertThat(queryInt(c,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'tasks' AND column_name = 'task_category_id'"))
                    .isEqualTo(1);
            // Scheduled visibility (V56, T04/D8): NULL means immediate, so
            // every pre-V56 task stays visible exactly as before.
            assertThat(queryInt(c,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'tasks' AND column_name = 'visible_from'"))
                    .isEqualTo(1);
            // Explicit-model default (V43): no internship is ever born legacy.
            assertThat(queryString(c,
                    "SELECT column_default FROM information_schema.columns WHERE table_name = 'internships' AND column_name = 'status'"))
                    .contains("APPROVED");
            // V52 on an EMPTY database: the reference sequences stay at their
            // V51 fresh positions (the backfill must not skip 00001 when no
            // legacy rows exist) — the very next nextval returns exactly 1.
            assertThat(queryLong(c, "SELECT nextval('finance_case_reference_seq')")).isEqualTo(1L);
            assertThat(queryLong(c, "SELECT nextval('payment_receipt_reference_seq')")).isEqualTo(1L);
            assertThat(queryLong(c, "SELECT nextval('document_reference_seq')")).isEqualTo(1L);
            assertThat(queryLong(c, "SELECT nextval('certificate_reference_seq')")).isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------
    // 2. V33 + legacy rows -> latest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V33 legacy rows (HR role, legacy statuses, unsupervised internship) migrate to the explicit model")
    void v33LegacyRowsMigrateToLatest() throws Exception {
        try (PostgreSQLContainer<?> legacy = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))) {
            legacy.start();
            flywayTo(legacy, "33").migrate();

            UUID uniId = UUID.randomUUID();
            UUID hrUserId = UUID.randomUUID();
            UUID adminUserId = UUID.randomUUID();
            UUID deptId = UUID.randomUUID();
            UUID candAccepted = UUID.randomUUID();
            UUID candCorrection = UUID.randomUUID();
            UUID appAccepted = UUID.randomUUID();
            UUID appCorrection = UUID.randomUUID();
            UUID internPlanned = UUID.randomUUID();
            UUID internActive = UUID.randomUUID();

            try (Connection c = driver(legacy)) {
                exec(c, "INSERT INTO universities (id, code, name, created_at, updated_at, version) VALUES (?, 'MIG33', 'Migration Uni', now(), now(), 0)", uniId);
                exec(c, "INSERT INTO departments (id, code, name, created_at, updated_at, version) VALUES (?, 'MIG33', 'Migration Dept', now(), now(), 0)", deptId);
                // V15-seeded role ids: HR ...004, ADMIN ...007.
                exec(c, "INSERT INTO users (id, email, password_hash, created_at, updated_at, version) VALUES (?, 'hr.legacy@test.tn', 'hash', now(), now(), 0)", hrUserId);
                exec(c, "INSERT INTO users (id, email, password_hash, created_at, updated_at, version) VALUES (?, 'admin.legacy@test.tn', 'hash', now(), now(), 0)", adminUserId);
                exec(c, "INSERT INTO user_roles (user_id, role_id) VALUES (?, 'b1000000-0000-0000-0000-000000000004')", hrUserId);
                exec(c, "INSERT INTO user_roles (user_id, role_id) VALUES (?, 'b1000000-0000-0000-0000-000000000007')", adminUserId);
                exec(c, "INSERT INTO candidates (id, university_id, national_id_hash, first_name, last_name, email, created_at, updated_at, version) VALUES (?, ?, 'hash-accepted', 'Legacy', 'Accepted', 'accepted.legacy@test.tn', now(), now(), 0)", candAccepted, uniId);
                exec(c, "INSERT INTO candidates (id, university_id, national_id_hash, first_name, last_name, email, created_at, updated_at, version) VALUES (?, ?, 'hash-correction', 'Legacy', 'Correction', 'correction.legacy@test.tn', now(), now(), 0)", candCorrection, uniId);
                exec(c, "INSERT INTO internship_applications (id, reference, candidate_id, status, created_at, updated_at, version) VALUES (?, 'APP-MIG33-1', ?, 'ACCEPTED', now(), now(), 0)", appAccepted, candAccepted);
                exec(c, "INSERT INTO internship_applications (id, reference, candidate_id, status, created_at, updated_at, version) VALUES (?, 'APP-MIG33-2', ?, 'NEEDS_CORRECTION', now(), now(), 0)", appCorrection, candCorrection);
                // V33 internships have no supervisor_user_id column yet (added by V35).
                exec(c, "INSERT INTO internships (id, reference, candidate_id, start_date, end_date, status, type, requirement, created_at, updated_at, version) VALUES (?, 'INT-MIG33-1', ?, '2026-01-05', '2026-06-05', 'PLANNED', 'PFE', 'OBLIGATOIRE', now(), now(), 0)", internPlanned, candAccepted);
                exec(c, "INSERT INTO internships (id, reference, candidate_id, start_date, end_date, status, type, requirement, created_at, updated_at, version) VALUES (?, 'INT-MIG33-2', ?, '2026-01-05', '2026-03-05', 'ACTIVE', 'PERFECTIONNEMENT', 'OBLIGATOIRE', now(), now(), 0)", internActive, candCorrection);

                assertThat(queryString(c, "SELECT status FROM internship_applications WHERE id = ?", appAccepted))
                        .isEqualTo("ACCEPTED");
            }

            var result = flywayTo(legacy, null).migrate();
            assertThat(result.success).isTrue();

            try (Connection c = driver(legacy)) {
                // V34: obsolete roles gone, the HR holder reassigned to ADMIN (never orphaned).
                assertThat(queryInt(c, "SELECT COUNT(*) FROM roles WHERE code IN ('HR','FINANCE','DIRECTOR')")).isZero();
                assertThat(queryInt(c, "SELECT COUNT(*) FROM roles WHERE code IN ('ADMIN','SUPERVISOR')")).isEqualTo(2);
                assertThat(queryInt(c, "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.code = 'ADMIN'", hrUserId)).isEqualTo(1);
                // V39/V43: legacy application statuses mapped (S1b assumption #15).
                assertThat(queryString(c, "SELECT status FROM internship_applications WHERE id = ?", appAccepted))
                        .isEqualTo("APPROVED");
                assertThat(queryString(c, "SELECT status FROM internship_applications WHERE id = ?", appCorrection))
                        .isEqualTo("MODIFICATION_REQUESTED");
                // V43: legacy internship statuses mapped.
                assertThat(queryString(c, "SELECT status FROM internships WHERE id = ?", internPlanned))
                        .isEqualTo("APPROVED");
                assertThat(queryString(c, "SELECT status FROM internships WHERE id = ?", internActive))
                        .isEqualTo("IN_PROGRESS");
                // V40: no internship left without a supervisor; every internship
                // gained exactly one ACTIVE assignment. The backfill picks the
                // OLDEST admin (V27's seeded admin.steg@steg.tn predates ours).
                assertThat(queryInt(c, "SELECT COUNT(*) FROM internships WHERE supervisor_user_id IS NULL")).isZero();
                assertThat(queryString(c, "SELECT u.email FROM internships i JOIN users u ON u.id = i.supervisor_user_id WHERE i.id = ?", internPlanned))
                        .isEqualTo("admin.steg@steg.tn");
                assertThat(queryInt(c, "SELECT COUNT(*) FROM internship_assignments WHERE internship_id = ? AND status = 'ACTIVE'", internPlanned)).isEqualTo(1);
                // V42: the same CIN hash still belongs to exactly one live row.
                assertThat(queryInt(c, "SELECT COUNT(*) FROM candidates WHERE national_id_hash = 'hash-accepted' AND deleted_at IS NULL")).isEqualTo(1);
                // Nothing legacy anywhere in the two status columns.
                assertThat(queryInt(c, "SELECT COUNT(*) FROM internship_applications WHERE status IN ('ACCEPTED','NEEDS_CORRECTION')")).isZero();
                assertThat(queryInt(c, "SELECT COUNT(*) FROM internships WHERE status IN ('PLANNED','ACTIVE','COMPLETED')")).isZero();
            }
            legacy.stop();
        }
    }

    // ------------------------------------------------------------------
    // 3. V33 + count-based FC-/PAY-/DOC- rows -> latest: the reference
    //    sequences must start ABOVE every existing numeric suffix (V52),
    //    because the old count-based scheme minted the CURRENT year's low
    //    numbers that a fresh START 1 sequence would immediately re-mint.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V33 rows with count-based FC-/PAY-/DOC- references backfill the sequences above every existing suffix (V52)")
    void v33LegacyReferenceRowsBackfillTheSequences() throws Exception {
        try (PostgreSQLContainer<?> legacy = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))) {
            legacy.start();
            flywayTo(legacy, "33").migrate();
            try (Connection c = driver(legacy)) {
                LegacyReferenceRows.seed(c);
            }

            var result = flywayTo(legacy, null).migrate();
            assertThat(result.success).isTrue();

            int year = java.time.Year.now().getValue();
            try (Connection c = driver(legacy)) {
                // V52 positions every sequence past the highest existing
                // numeric suffix — the very next nextval() returns exactly
                // max + 1 (is_called = false). WITHOUT V52 these nextvals
                // return 1 and the minted references below equal the seeded
                // FC-2026-00001 / PAY-2026-00001 / DOC-2026-00001 rows, so the
                // "no collision" assertions fail (the production bug).
                long fcNext = queryLong(c, "SELECT nextval('finance_case_reference_seq')");
                long payNext = queryLong(c, "SELECT nextval('payment_receipt_reference_seq')");
                long docNext = queryLong(c, "SELECT nextval('document_reference_seq')");
                assertThat(fcNext).as("finance_case_reference_seq above legacy max")
                        .isGreaterThan(LegacyReferenceRows.MAX_FC_SUFFIX);
                assertThat(payNext).as("payment_receipt_reference_seq above legacy max")
                        .isGreaterThan(LegacyReferenceRows.MAX_PAY_SUFFIX);
                assertThat(docNext).as("document_reference_seq above legacy max")
                        .isGreaterThan(LegacyReferenceRows.MAX_DOC_SUFFIX);

                // The references the REAL services mint (FinanceService:
                // String.format("FC-%d-%05d", year, seq); same shape for PAY-
                // and DOC-) collide with nothing — the unique constraints hold
                // on the first post-upgrade writes. The Spring-driven proof
                // through the actual service methods is
                // SequenceBackfillServiceIntegrationTest.
                assertThat(queryInt(c, "SELECT COUNT(*) FROM finance_cases WHERE reference = ?",
                        String.format("FC-%d-%05d", year, fcNext))).isZero();
                assertThat(queryInt(c, "SELECT COUNT(*) FROM payment_receipts WHERE reference = ?",
                        String.format("PAY-%d-%05d", year, payNext))).isZero();
                assertThat(queryInt(c, "SELECT COUNT(*) FROM documents WHERE reference = ?",
                        String.format("DOC-%d-%05d", year, docNext))).isZero();

                // New numbers strictly greater than every seeded reference:
                // no existing suffix (any year) is re-minted.
                assertThat(queryInt(c,
                        "SELECT COUNT(*) FROM finance_cases WHERE reference ~ ('^FC-[0-9]{4}-' || LPAD(?, 5, '0') || '$')",
                        String.valueOf(fcNext))).isZero();
                assertThat(queryInt(c,
                        "SELECT COUNT(*) FROM payment_receipts WHERE reference ~ ('^PAY-[0-9]{4}-' || LPAD(?, 5, '0') || '$')",
                        String.valueOf(payNext))).isZero();
                assertThat(queryInt(c,
                        "SELECT COUNT(*) FROM documents WHERE reference ~ ('^DOC-[0-9]{4}-' || LPAD(?, 5, '0') || '$')",
                        String.valueOf(docNext))).isZero();
            }
            legacy.stop();
        }
    }
}
