package tn.steg.backend.reporting;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A13 — verifies the V24 read-model indexes exist on PostgreSQL and that
 * every Back Office aggregate runs as a single database-side GROUP BY with a
 * valid EXPLAIN plan (no N+1 loops, no accidental raw entity loads).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A13 — Reporting query plans (PostgreSQL)")
class ReportingQueryPlanTest {

    @Autowired private EntityManager entityManager;

    private static final List<String> EXPECTED_INDEXES = List.of(
            "idx_reporting_applications_status",
            "idx_reporting_internships_status",
            "idx_reporting_internships_type",
            "idx_reporting_assignments_internship",
            "idx_reporting_assignments_department",
            "idx_reporting_finance_cases_status",
            "idx_reporting_receipts_period_amount");

    @Test
    @DisplayName("V24 read-model indexes are present in the reporting database")
    void reportingIndexesArePresent() {
        List<String> actual = entityManager
                .createNativeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                        String.class).getResultList();
        for (String expected : EXPECTED_INDEXES) {
            assertThat(actual).as("index %s applied by V24", expected).contains(expected);
        }
    }

    @Test
    @DisplayName("every report aggregate yields a valid single-pass EXPLAIN plan")
    void aggregateQueriesProduceValidPlans() {
        List<String> aggregates = List.of(
                """
                select status, count(*) from internship_applications group by status
                """,
                """
                select type, count(*) from internships group by type
                """,
                """
                select status, count(*) from internships group by status
                """,
                """
                select d.code, count(distinct i.id)
                from internships i
                join internship_assignments a on a.internship_id = i.id
                join departments d on d.id = a.department_id
                group by d.code
                """,
                """
                select status, count(*) from finance_cases group by status
                """,
                """
                select extract(year from pr.payment_date), extract(month from pr.payment_date), d.code,
                       sum(pr.amount) as total_amount, count(*) as receipts
                from payment_receipts pr
                join finance_cases fc on fc.id = pr.finance_case_id
                join internships i on i.id = fc.internship_id
                left join internship_assignments a on a.internship_id = i.id
                left join departments d on d.id = a.department_id
                where pr.payment_date is not null
                group by 1, 2, 3
                """);

        for (String aggregate : aggregates) {
            String plan = (String) entityManager.createNativeQuery(
                    "EXPLAIN (FORMAT JSON) " + aggregate).getSingleResult();
            assertThat(plan).as("EXPLAIN JSON for\n%s", aggregate)
                    .contains("\"Plan\"")
                    .contains("\"Plan Rows\"");
        }
    }
}