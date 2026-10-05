package tn.steg.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1b data migration proof (audit assumption #15). This test does NOT boot
 * Spring: it drives Flyway itself so it can prove the exact sequence the rule
 * demands — migrate to the LAST version that still had the legacy values,
 * insert rows carrying each legacy value, migrate to the new version and assert
 * every row landed on its mapped explicit status.
 *
 * <p>Application: {@code ACCEPTED -> APPROVED}, {@code NEEDS_CORRECTION ->
 * MODIFICATION_REQUESTED}. Internship: {@code PLANNED -> APPROVED},
 * {@code ACTIVE -> IN_PROGRESS}, {@code COMPLETED -> VALIDATED}.
 *
 * <p>It also proves the data really was reachable before the migration: the
 * legacy statuses are inserted as plain VARCHAR values (there is no enum or
 * CHECK constraint on the column), which is exactly how production rows would
 * still look after the previous, partial migration V39.
 */
@Testcontainers
@DisplayName("V43 legacy status removal migration (Testcontainers)")
class LegacyStatusRemovalMigrationIntegrationTest {

    private static final String LEGACY_LAST_VERSION = "42";
    private static final String NEW_VERSION = "43";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @BeforeAll
    static void migrateLegacyDataForward() throws Exception {
        POSTGRES.start();

        // 1. Migrate to the LAST version that still had the legacy values.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(LEGACY_LAST_VERSION)
                .load()
                .migrate();

        // 2. Insert rows carrying each legacy value, exactly as production rows
        //    would still look (the status columns are plain VARCHAR).
        try (Connection connection = driver()) {
            UUID universityId = insertUniversity(connection, "MIG");
            for (String status : List.of("ACCEPTED", "NEEDS_CORRECTION", "SUBMITTED", "APPROVED", "REJECTED")) {
                // One candidate per application: uq_applications_candidate_id_active
                // allows only ONE non-withdrawn application per candidate.
                UUID candidateId = insertCandidate(connection, universityId, status);
                insertApplication(connection, candidateId, status);
            }
            UUID internCandidate = insertCandidate(connection, universityId, "INTERN");
            for (String status : List.of("PLANNED", "ACTIVE", "COMPLETED",
                    "APPROVED", "IN_PROGRESS", "VALIDATED", "RECEIPT_ISSUED")) {
                insertInternship(connection, internCandidate, status);
            }

            // Sanity: the legacy rows really exist BEFORE the new migration, so
            // the assertions cannot pass on an empty table.
            assertThat(statusesOf(connection, "internship_applications"))
                    .contains("ACCEPTED", "NEEDS_CORRECTION");
            assertThat(statusesOf(connection, "internships"))
                    .contains("PLANNED", "ACTIVE", "COMPLETED");
        }

        // 3. Migrate to the new version.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(NEW_VERSION)
                .load()
                .migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    @DisplayName("every legacy application/internship status is mapped to its explicit §4 state")
    void legacyStatusesAreMapped() throws Exception {
        try (Connection connection = driver()) {
            assertThat(statusesOf(connection, "internship_applications"))
                    .as("no legacy application status survives")
                    .doesNotContain("ACCEPTED", "NEEDS_CORRECTION");
            assertThat(statusesOf(connection, "internships"))
                    .as("no legacy internship status survives")
                    .doesNotContain("PLANNED", "ACTIVE", "COMPLETED");

            // The mapped values are present exactly as documented in #15.
            assertThat(countOf(connection, "internship_applications", "APPROVED")).isPositive();
            assertThat(countOf(connection, "internship_applications", "MODIFICATION_REQUESTED")).isPositive();
            assertThat(countOf(connection, "internships", "APPROVED")).isPositive();
            assertThat(countOf(connection, "internships", "IN_PROGRESS")).isPositive();
            assertThat(countOf(connection, "internships", "VALIDATED")).isPositive();

            // Rows that were ALREADY explicit are untouched (no collateral).
            assertThat(countOf(connection, "internship_applications", "SUBMITTED")).isPositive();
            assertThat(countOf(connection, "internship_applications", "REJECTED")).isPositive();
            assertThat(countOf(connection, "internships", "RECEIPT_ISSUED")).isPositive();
        }
    }

    @Test
    @DisplayName("a NEW internship is no longer born with a legacy default status")
    void newInternshipsDefaultToTheExplicitFirstState() throws Exception {
        try (Connection connection = driver()) {
            // The column default is asserted directly: an insert that omits
            // `status` must produce the explicit first state of the §4 chain.
            String defaultValue;
            try (PreparedStatement probe = connection.prepareStatement(
                    "SELECT column_default FROM information_schema.columns "
                            + "WHERE table_name = 'internships' AND column_name = 'status'");
                 ResultSet rs = probe.executeQuery()) {
                assertThat(rs.next()).isTrue();
                defaultValue = rs.getString(1);
            }
            assertThat(defaultValue)
                    .as("the legacy PLANNED default is gone")
                    .isNotNull()
                    .contains("APPROVED")
                    .doesNotContain("PLANNED");

            // And the default really applies: no status column in the INSERT.
            UUID candidateId = selectOne(connection, "SELECT id FROM candidates LIMIT 1");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO internships (id, reference, candidate_id, start_date, end_date, "
                            + "type, requirement, created_at, updated_at, version) "
                            + "VALUES (?, ?, ?, CURRENT_DATE, CURRENT_DATE + 30, 'PFE', 'OBLIGATOIRE', "
                            + "now(), now(), 0)")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, "INT-MIG-DEF-" + UUID.randomUUID().toString().substring(0, 8));
                insert.setObject(3, candidateId);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            assertThat(statusOfLatestInsert(connection))
                    .isEqualTo("APPROVED")
                    .doesNotContain("PLANNED");
        }
    }

    // -------------------------------------------------------------------------
    // JDBC helpers (plain SQL: this test predates the ORM on purpose)
    // -------------------------------------------------------------------------

    private static Connection driver() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static UUID insertUniversity(Connection connection, String prefix) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO universities (id, code, name, active, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, true, now(), now(), 0)")) {
            ps.setObject(1, id);
            ps.setString(2, prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
            ps.setString(3, "Migration University " + prefix);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insertCandidate(Connection connection, UUID universityId, String label) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO candidates (id, university_id, national_id_hash, first_name, last_name, email, "
                        + "created_at, updated_at, version) VALUES (?, ?, ?, 'Mig', ?, ?, now(), now(), 0)")) {
            ps.setObject(1, id);
            ps.setObject(2, universityId);
            ps.setString(3, "MIG-HASH-" + id);
            ps.setString(4, label);
            ps.setString(5, "mig_" + label.toLowerCase() + "_" + id + "@steg.tn");
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertApplication(Connection connection, UUID candidateId, String status) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO internship_applications (id, reference, candidate_id, status, submission_date, "
                        + "submitted_online, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, CURRENT_DATE, true, now(), now(), 0)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, "APP-MIG-" + status + "-" + UUID.randomUUID().toString().substring(0, 6));
            ps.setObject(3, candidateId);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    private static void insertInternship(Connection connection, UUID candidateId,
                                        String status) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO internships (id, reference, candidate_id, start_date, end_date, "
                        + "type, requirement, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, CURRENT_DATE, CURRENT_DATE + 30, 'PFE', 'OBLIGATOIRE', ?, "
                        + "now(), now(), 0)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, "INT-MIG-" + status + "-" + UUID.randomUUID().toString().substring(0, 6));
            ps.setObject(3, candidateId);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    private static UUID selectOne(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return (UUID) rs.getObject(1);
        }
    }

    private static String statusOfLatestInsert(Connection connection) throws Exception {
        return selectString(connection,
                "SELECT status FROM internships ORDER BY created_at DESC, reference DESC LIMIT 1");
    }

    private static String selectString(Connection connection, String sql) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static List<String> statusesOf(Connection connection, String table) throws Exception {
        List<String> statuses = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT DISTINCT status FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                statuses.add(rs.getString(1));
            }
        }
        return statuses;
    }

    private static long countOf(Connection connection, String table, String status) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE status = ?")) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
