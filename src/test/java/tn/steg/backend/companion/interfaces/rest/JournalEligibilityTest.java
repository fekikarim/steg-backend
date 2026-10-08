package tn.steg.backend.companion.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.testsupport.FakeAiCompletionClientConfiguration;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * T09/B5 — journal eligibility window (D3/D3b, BR-20/BR-21/BR-61,
 * ST-JRN-01/02): server-computed in {@code Africa/Tunis}, 14 days shorter than
 * 3 calendar months and 30 days otherwise (exactly 3 months included), window
 * {@code [end − N, end]} inclusive and still open after the end, plus the
 * authorization/IDOR surface of the read. No AI call is ever made here.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeAiCompletionClientConfiguration.class})
@DisplayName("T09/B5 — journal eligibility window (Testcontainers)")
class JournalEligibilityTest extends JournalTestSupport {

    /** Server zone of record: the test derives its expectations from it, never from UTC or the JVM default. */
    private static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");

    private JsonNode eligibility(String token, java.util.UUID internshipId) throws Exception {
        MvcResult result = getJson("/api/internships/" + internshipId + "/journal-eligibility", token);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("short internship (< 3 calendar months): 14-day window, one day before it a coded not-eligible")
    void shortInternshipUsesFourteenDayWindow() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        // Start 2 months before the end → shorter than 3 months → 14 days. The
        // window opens at end − 14, so an end of today + 15 opens tomorrow.
        Intern intern = createStudent("S", supervisorUser, today.minusMonths(2), today.plusDays(15));

        JsonNode before = eligibility(intern.token(), intern.internshipId());
        assertThat(before.path("eligible").asBoolean()).isFalse();
        assertThat(before.path("reason").asText()).isEqualTo("BEFORE_WINDOW");
        assertThat(before.path("windowDays").asInt()).isEqualTo(14);
        assertThat(before.path("opensAt").asText()).isEqualTo(today.plusDays(1).toString());
        assertThat(before.path("daysUntilOpen").asInt()).isEqualTo(1);
        assertThat(before.path("closesAt").asText()).isEqualTo(today.plusDays(15).toString());

        // The very first eligible day: opensAt == today.
        Intern first = createStudent("S1", supervisorUser, today.minusMonths(2), today.plusDays(14));
        JsonNode onFirstDay = eligibility(first.token(), first.internshipId());
        assertThat(onFirstDay.path("eligible").asBoolean()).isTrue();
        assertThat(onFirstDay.path("reason").asText()).isEqualTo("ELIGIBLE_WINDOW");
        assertThat(onFirstDay.path("daysUntilOpen").asInt()).isZero();
    }

    @Test
    @DisplayName("exactly 3 calendar months takes the 30-day branch (D3)")
    void exactlyThreeMonthsUsesThirtyDayWindow() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        // end == start.plusMonths(3) exactly → NOT shorter than 3 months.
        LocalDate start = today.minusMonths(3);
        LocalDate end = start.plusMonths(3);
        Intern intern = createStudent("M", supervisorUser, start, end);

        JsonNode body = eligibility(intern.token(), intern.internshipId());
        assertThat(body.path("windowDays").asInt()).isEqualTo(30);
        assertThat(body.path("opensAt").asText()).isEqualTo(end.minusDays(30).toString());
        assertThat(body.path("eligible").asBoolean()).isTrue();
        assertThat(body.path("reason").asText()).isEqualTo("ELIGIBLE_WINDOW");

        // One day shorter than 3 months → the 14-day branch (the boundary pair).
        Intern shorter = createStudent("M1", supervisorUser, start, end.minusDays(1));
        JsonNode shorterBody = eligibility(shorter.token(), shorter.internshipId());
        assertThat(shorterBody.path("windowDays").asInt()).isEqualTo(14);
        assertThat(shorterBody.path("opensAt").asText())
                .isEqualTo(end.minusDays(1).minusDays(14).toString());
    }

    @Test
    @DisplayName("inside the window, on the final day and after the end (late) are all eligible")
    void insideFinalDayAndLateAreEligible() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);

        Intern inside = createStudent("W", supervisorUser, today.minusMonths(4), today.plusDays(10));
        JsonNode insideBody = eligibility(inside.token(), inside.internshipId());
        assertThat(insideBody.path("eligible").asBoolean()).isTrue();
        assertThat(insideBody.path("reason").asText()).isEqualTo("ELIGIBLE_WINDOW");

        Intern lastDay = createStudent("E", supervisorUser, today.minusMonths(4), today);
        JsonNode lastDayBody = eligibility(lastDay.token(), lastDay.internshipId());
        assertThat(lastDayBody.path("eligible").asBoolean()).isTrue();
        assertThat(lastDayBody.path("reason").asText()).isEqualTo("ELIGIBLE_WINDOW");
        assertThat(lastDayBody.path("closesAt").asText()).isEqualTo(today.toString());

        // D3b: after the end the window stays open with a late reason.
        Intern late = createStudent("L", supervisorUser, today.minusMonths(5), today.minusDays(1));
        JsonNode lateBody = eligibility(late.token(), late.internshipId());
        assertThat(lateBody.path("eligible").asBoolean()).isTrue();
        assertThat(lateBody.path("reason").asText()).isEqualTo("ELIGIBLE_LATE");
        assertThat(lateBody.path("daysUntilOpen").asInt()).isZero();
    }

    @Test
    @DisplayName("a cancelled internship is never eligible")
    void cancelledInternshipIsNotEligible() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("C", supervisorUser, today.minusMonths(4), today.plusDays(2));

        internshipLifecycleService.cancel(intern.internshipId(), adminPrincipal);

        JsonNode body = eligibility(intern.token(), intern.internshipId());
        assertThat(body.path("eligible").asBoolean()).isFalse();
        assertThat(body.path("reason").asText()).isEqualTo("CANCELLED");
        assertThat(body.path("opensAt").isNull()).isTrue();
    }

    @ParameterizedTest(name = "approved {0} of {1} → belowThreshold {2}")
    @CsvSource({
            "0, 0, false",
            "4, 4, false",
            "3, 4, false",
            "2, 3, true",
            "1, 4, true",
            "74, 100, true",
            "75, 100, false",
    })
    @DisplayName("BR-24/A2: fewer than 75 % approved warns — exactly 75 % does not")
    void completionBoundariesUseTheSharedPolicy(int approved, int total, boolean expectedWarning)
            throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("R" + approved + "x" + total, supervisorUser,
                today.minusMonths(4), today.plusDays(5));
        for (int i = 0; i < total; i++) {
            addTask(intern.internshipId(), supervisorUser, "Task " + i + " of " + run,
                    i < approved ? TaskStatus.APPROVED : TaskStatus.IN_PROGRESS, today.plusDays(3));
        }

        JsonNode body = eligibility(intern.token(), intern.internshipId());
        assertThat(body.path("taskCount").asInt()).isEqualTo(total);
        assertThat(body.path("approvedTasks").asInt()).isEqualTo(approved);
        assertThat(body.path("belowThreshold").asBoolean()).isEqualTo(expectedWarning);
    }

    @Test
    @DisplayName("A2 denominator: cancelled tasks and not-yet-visible scheduled tasks do not count")
    void denominatorExcludesCancelledAndHiddenTasks() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("D", supervisorUser, today.minusMonths(4), today.plusDays(5));
        addTask(intern.internshipId(), supervisorUser, "Approved " + run, TaskStatus.APPROVED, today);
        addTask(intern.internshipId(), supervisorUser, "Cancelled " + run, TaskStatus.CANCELLED, today);
        var hidden = addTask(intern.internshipId(), supervisorUser, "Hidden " + run, TaskStatus.APPROVED, today);
        hidden.setVisibleFrom(java.time.Instant.now().plusSeconds(86_400));
        taskRepository.saveTask(hidden);

        JsonNode body = eligibility(intern.token(), intern.internshipId());
        assertThat(body.path("taskCount").asInt()).isEqualTo(1);
        assertThat(body.path("approvedTasks").asInt()).isEqualTo(1);
        assertThat(body.path("belowThreshold").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("IDOR: another student is 404, an unrelated supervisor is 404, the own supervisor and Admin read it")
    void scopeIsEnforced() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern mine = createStudent("A", supervisorUser, today.minusMonths(4), today.minusDays(1));
        Intern other = createStudent("B", supervisorUser, today.minusMonths(4), today.minusDays(1));

        // Method security refuses a non-participant FIRST, and identically for an
        // existing-but-foreign and an unknown id → the 403 leaks no existence.
        assertThat(getJson("/api/internships/" + mine.internshipId() + "/journal-eligibility",
                other.token()).getResponse().getStatus()).isEqualTo(403);
        assertThat(getJson("/api/internships/" + mine.internshipId() + "/journal-eligibility",
                otherSupervisorToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(getJson("/api/internships/" + java.util.UUID.randomUUID() + "/journal-eligibility",
                mine.token()).getResponse().getStatus()).isEqualTo(403);
        // Participants and Admin read it.
        assertThat(getJson("/api/internships/" + mine.internshipId() + "/journal-eligibility",
                supervisorToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/internships/" + mine.internshipId() + "/journal-eligibility",
                adminToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/internships/" + mine.internshipId() + "/journal-eligibility",
                null).getResponse().getStatus()).isEqualTo(401);
        // An Admin (whose guard passes) reaches the row scope: a manipulated id is a 404, never a leak or a 500.
        assertThat(getJson("/api/internships/" + java.util.UUID.randomUUID() + "/journal-eligibility",
                adminToken).getResponse().getStatus()).isEqualTo(404);

        verify(fakeAi, never()).complete(any(), anyList());
    }
}
