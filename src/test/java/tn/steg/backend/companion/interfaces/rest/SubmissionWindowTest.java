package tn.steg.backend.companion.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.companion.domain.model.Deliverable;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.testsupport.FakeAiCompletionClientConfiguration;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * T10/B7 — the final-week submission window (BR-22, ST-VAL-01, D3): computed
 * server-side in {@code Africa/Tunis} as {@code [end − 7, end]} for every
 * internship type, readable by participants, and enforced on the submission of
 * documents that declare their kind (B8). Outside the window the server
 * refuses — before the final week AND after the end, because late is not
 * accepted (the documented T10 edge case: the app explains and offers the
 * supervisor chat, never an override).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeAiCompletionClientConfiguration.class})
@DisplayName("T10/B7 — final-week submission window (Testcontainers)")
class SubmissionWindowTest extends JournalTestSupport {

    /** Server zone of record: expectations derive from it, never from UTC. */
    private static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");

    private JsonNode window(String token, UUID internshipId) throws Exception {
        MvcResult result = getJson("/api/internships/" + internshipId + "/submission-window", token);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Creates a DRAFT deliverable through the real multipart upload endpoint. */
    private UUID createDeliverable(Intern intern, String documentKind) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "document.pdf",
                "application/pdf", ("%PDF-1.4 T10 " + uid()).getBytes(StandardCharsets.UTF_8));
        var request = multipart("/api/internships/" + intern.internshipId() + "/deliverables")
                .file(file)
                .param("title", "Document " + uid())
                .header("Authorization", "Bearer " + intern.token());
        if (documentKind != null) {
            request.param("documentKind", documentKind);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("deliverable creation must succeed: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private MvcResult submit(UUID deliverableId, String token) throws Exception {
        return postJson("/api/internships/deliverables/" + deliverableId + "/submit", token, null);
    }

    /**
     * Reads a deliverable through the domain port: the infra bean inherits two
     * findById overloads (port + CrudRepository) and direct calls are ambiguous.
     */
    private Deliverable deliverable(UUID id) {
        tn.steg.backend.companion.domain.repository.DeliverableRepository port = deliverableRepository;
        return port.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("BR-22: opens exactly 7 days before the end, closes on the end date, refuses late")
    void windowBoundaries() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);

        // Not open yet: end − 7 is tomorrow.
        Intern before = createStudent("B7b", supervisorUser, today.minusMonths(2), today.plusDays(8));
        JsonNode beforeBody = window(before.token(), before.internshipId());
        assertThat(beforeBody.path("open").asBoolean()).isFalse();
        assertThat(beforeBody.path("reason").asText()).isEqualTo("BEFORE_WINDOW");
        assertThat(beforeBody.path("opensAt").asText()).isEqualTo(today.plusDays(1).toString());
        assertThat(beforeBody.path("closesAt").asText()).isEqualTo(today.plusDays(8).toString());
        assertThat(beforeBody.path("daysUntilOpen").asInt()).isEqualTo(1);
        assertThat(beforeBody.path("windowDays").asInt()).isEqualTo(7);

        // The very first eligible day: end − 7 == today.
        Intern first = createStudent("B7f", supervisorUser, today.minusMonths(2), today.plusDays(7));
        JsonNode firstBody = window(first.token(), first.internshipId());
        assertThat(firstBody.path("open").asBoolean()).isTrue();
        assertThat(firstBody.path("reason").asText()).isEqualTo("OPEN");
        assertThat(firstBody.path("daysUntilOpen").asInt()).isZero();

        // The internship end date itself is always inside.
        Intern last = createStudent("B7l", supervisorUser, today.minusMonths(2), today);
        assertThat(window(last.token(), last.internshipId()).path("open").asBoolean()).isTrue();

        // Late is refused (unlike the journal-generation D3b window).
        Intern late = createStudent("B7t", supervisorUser, today.minusMonths(3), today.minusDays(1));
        JsonNode lateBody = window(late.token(), late.internshipId());
        assertThat(lateBody.path("open").asBoolean()).isFalse();
        assertThat(lateBody.path("reason").asText()).isEqualTo("AFTER_WINDOW");
    }

    @Test
    @DisplayName("a cancelled internship never opens the window")
    void cancelledInternshipIsClosed() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("B7c", supervisorUser, today.minusMonths(2), today.plusDays(2));
        internshipLifecycleService.cancel(intern.internshipId(), adminPrincipal);

        JsonNode body = window(intern.token(), intern.internshipId());
        assertThat(body.path("open").asBoolean()).isFalse();
        assertThat(body.path("reason").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a declared REPORT is refused before the final week with the coded 422, and stays DRAFT")
    void declaredDocumentCannotBeSubmittedEarly() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("B7e", supervisorUser, today.minusMonths(2), today.plusDays(20));
        UUID deliverableId = createDeliverable(intern, "REPORT");

        MvcResult refused = submit(deliverableId, intern.token());
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(refused.getResponse().getContentAsString())
                .path("error").asText()).isEqualTo("SUBMISSION_NOT_IN_WINDOW");

        assertThat(deliverable(deliverableId).getStatus().name())
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("a declared document is refused after the internship ended (late is not accepted)")
    void declaredDocumentCannotBeSubmittedLate() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("B7L", supervisorUser, today.minusMonths(4), today.minusDays(2));
        UUID deliverableId = createDeliverable(intern, "JOURNAL");

        MvcResult refused = submit(deliverableId, intern.token());
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(refused.getResponse().getContentAsString())
                .path("error").asText()).isEqualTo("SUBMISSION_NOT_IN_WINDOW");
    }

    @Test
    @DisplayName("inside the final week both documents submit and the first one moves REPORT_SUBMITTED")
    void bothDocumentsSubmitInsideTheWindow() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("B7i", supervisorUser, today.minusMonths(2), today.plusDays(3));
        // The fixture internship is APPROVED until started; move it into its
        // running phase so the real first-submission transition can fire.
        internshipLifecycleService.transition(intern.internshipId(), InternshipStatus.IN_PROGRESS,
                "Started for the B7 window run", adminPrincipal);
        UUID report = createDeliverable(intern, "REPORT");
        UUID journal = createDeliverable(intern, "JOURNAL");

        assertThat(submit(report, intern.token()).getResponse().getStatus()).isEqualTo(200);
        assertThat(submit(journal, intern.token()).getResponse().getStatus()).isEqualTo(200);

        assertThat(deliverable(journal).getStatus().name())
                .isEqualTo("SUBMITTED");
        // BR-29: the first submission while IN_PROGRESS drives the lifecycle.
        assertThat(internshipRepository.findById(intern.internshipId()).orElseThrow().getStatus())
                .isEqualTo(InternshipStatus.REPORT_SUBMITTED);
    }

    @Test
    @DisplayName("an ordinary deliverable without a declared kind keeps its existing lifecycle (no window)")
    void undeclaredDeliverableKeepsItsLifecycle() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("B7u", supervisorUser, today.minusMonths(2), today.plusDays(20));
        UUID deliverableId = createDeliverable(intern, null);

        assertThat(submit(deliverableId, intern.token()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("an unknown declared kind is a coded 422, never a silently ignored field")
    void unknownKindIsRefused() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("B7k", supervisorUser, today.minusMonths(2), today.plusDays(20));

        MockMultipartFile file = new MockMultipartFile("file", "document.pdf",
                "application/pdf", ("%PDF-1.4 T10 " + uid()).getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/internships/" + intern.internshipId() + "/deliverables")
                        .file(file)
                        .param("title", "Document " + uid())
                        .param("documentKind", "BANANA")
                        .header("Authorization", "Bearer " + intern.token()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("error").asText()).isEqualTo("INVALID_DOCUMENT_KIND");
    }

    @Test
    @DisplayName("IDOR: the window read is participant-scoped (403 for outsiders, 404 for a vanished row)")
    void windowScopeIsEnforced() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern mine = createStudent("B7s", supervisorUser, today.minusMonths(2), today.plusDays(3));
        Intern other = createStudent("B7o", supervisorUser, today.minusMonths(2), today.plusDays(3));

        assertThat(getJson("/api/internships/" + mine.internshipId() + "/submission-window",
                other.token()).getResponse().getStatus()).isEqualTo(403);
        assertThat(getJson("/api/internships/" + mine.internshipId() + "/submission-window",
                otherSupervisorToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(getJson("/api/internships/" + UUID.randomUUID() + "/submission-window",
                mine.token()).getResponse().getStatus()).isEqualTo(403);
        assertThat(getJson("/api/internships/" + mine.internshipId() + "/submission-window",
                null).getResponse().getStatus()).isEqualTo(401);
        assertThat(getJson("/api/internships/" + mine.internshipId() + "/submission-window",
                supervisorToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/internships/" + mine.internshipId() + "/submission-window",
                adminToken).getResponse().getStatus()).isEqualTo(200);
        // Admin passes the guard and reaches the row scope.
        assertThat(getJson("/api/internships/" + UUID.randomUUID() + "/submission-window",
                adminToken).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("IDOR: a foreign student cannot submit someone else's declared document")
    void foreignSubmitIsRefused() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern mine = createStudent("B7x", supervisorUser, today.minusMonths(2), today.plusDays(3));
        Intern other = createStudent("B7y", supervisorUser, today.minusMonths(2), today.plusDays(3));
        UUID deliverableId = createDeliverable(mine, "REPORT");

        assertThat(submit(deliverableId, other.token()).getResponse().getStatus()).isEqualTo(403);
        assertThat(submit(deliverableId, null).getResponse().getStatus()).isEqualTo(401);
        assertThat(deliverable(deliverableId).getStatus().name())
                .isEqualTo("DRAFT");
    }
}
