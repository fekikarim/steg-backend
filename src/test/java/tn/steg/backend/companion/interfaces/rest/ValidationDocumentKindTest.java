package tn.steg.backend.companion.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.messaging.application.MessagingService;
import tn.steg.backend.testsupport.FakeAiCompletionClientConfiguration;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * T10/B8 (explicit document kind, D4b) and SU-VAL-01 (the supervisor
 * registers a received document as the journal or the report) — the identity
 * of the two validation documents becomes data instead of the back office's
 * order inference (BR-33), the admin queue resolves the explicit kinds, and
 * the chat attachment remembers which document it came from so the
 * long-press registration targets exactly what was received.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeAiCompletionClientConfiguration.class})
@DisplayName("T10/B8 + SU-VAL-01 — explicit validation document kind (Testcontainers)")
class ValidationDocumentKindTest extends JournalTestSupport {

    private static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");

    @Autowired private MessagingService messagingService;

    /** Creates a DRAFT deliverable through the real multipart upload endpoint. */
    private UUID createDeliverable(Intern intern, String title, String documentKind) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "document.pdf",
                "application/pdf", ("%PDF-1.4 T10 " + uid()).getBytes(StandardCharsets.UTF_8));
        var request = multipart("/api/internships/" + intern.internshipId() + "/deliverables")
                .file(file)
                .param("title", title)
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

    private MvcResult register(UUID deliverableId, String kind, String token) throws Exception {
        String body = kind == null ? "{}" : "{\"documentKind\":\"" + kind + "\"}";
        return postJson("/api/internships/deliverables/" + deliverableId + "/document-kind", token, body);
    }

    private String kindOf(UUID deliverableId, String token) throws Exception {
        MvcResult result = getJson("/api/internships/deliverables/" + deliverableId, token);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode kind = objectMapper.readTree(result.getResponse().getContentAsString()).path("documentKind");
        return kind.isNull() || kind.isMissingNode() ? null : kind.asText();
    }

    /** Submits inside the open final week (end = today + 2). */
    private void submitInsideWindow(UUID deliverableId, Intern intern) throws Exception {
        MvcResult submitted = postJson("/api/internships/deliverables/" + deliverableId + "/submit",
                intern.token(), null);
        assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("B8: the student declares the document kind at upload and it round-trips")
    void studentDeclaresTheKindAtUpload() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("K1", supervisorUser, today.minusMonths(2), today.plusDays(20));

        UUID report = createDeliverable(intern, "Rapport de stage", "report");
        assertThat(kindOf(report, intern.token())).isEqualTo("REPORT");

        UUID plain = createDeliverable(intern, "Document libre", null);
        assertThat(kindOf(plain, intern.token())).isNull();
    }

    @Test
    @DisplayName("SU-VAL-01: the supervisor registers a received document as the journal, mobile-audited")
    void supervisorRegistersADocument() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("K2", supervisorUser, today.minusMonths(2), today.plusDays(2));
        UUID deliverableId = createDeliverable(intern, "Journal de stage", null);
        submitInsideWindow(deliverableId, intern);

        MvcResult registered = register(deliverableId, "JOURNAL", supervisorToken);
        assertThat(registered.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(registered.getResponse().getContentAsString())
                .path("documentKind").asText()).isEqualTo("JOURNAL");

        // Audited as metadata only: the mobile supervisor's action carries MOBILE.
        MvcResult audit = getJson("/api/audit?action=VALIDATION_DOCUMENT_REGISTERED&entityId="
                + deliverableId, adminToken);
        assertThat(audit.getResponse().getStatus()).isEqualTo(200);
        JsonNode rows = objectMapper.readTree(audit.getResponse().getContentAsString()).path("content");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("source").asText()).isEqualTo("MOBILE");
        assertThat(rows.get(0).path("newValues").asText()).contains("JOURNAL");
    }

    @Test
    @DisplayName("one document per kind: registering another document moves the registration")
    void registeringAnotherDocumentMovesTheKind() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("K3", supervisorUser, today.minusMonths(2), today.plusDays(2));
        UUID first = createDeliverable(intern, "Premier rapport", null);
        UUID second = createDeliverable(intern, "Rapport corrige", null);
        submitInsideWindow(first, intern);
        submitInsideWindow(second, intern);

        assertThat(register(first, "REPORT", supervisorToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(register(second, "REPORT", supervisorToken).getResponse().getStatus()).isEqualTo(200);

        assertThat(kindOf(second, supervisorToken)).isEqualTo("REPORT");
        assertThat(kindOf(first, supervisorToken)).isNull();
    }

    @Test
    @DisplayName("a validated document keeps its kind, and its kind cannot be taken by another document")
    void validatedDocumentsKeepTheirKind() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("K4", supervisorUser, today.minusMonths(2), today.plusDays(2));
        UUID registered = createDeliverable(intern, "Rapport final", null);
        UUID other = createDeliverable(intern, "Autre document", null);
        submitInsideWindow(registered, intern);
        submitInsideWindow(other, intern);
        assertThat(register(registered, "REPORT", supervisorToken).getResponse().getStatus()).isEqualTo(200);

        MvcResult validated = postJson("/api/internships/deliverables/" + registered + "/validate",
                adminToken, "{\"comment\":\"Conforme.\"}");
        assertThat(validated.getResponse().getStatus()).isEqualTo(200);

        // The validated document's kind is locked…
        MvcResult change = register(registered, "JOURNAL", supervisorToken);
        assertThat(change.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(change.getResponse().getContentAsString())
                .path("error").asText()).isEqualTo("DELIVERABLE_ALREADY_VALIDATED");

        // …and no other document may claim the kind the Admin already validated.
        MvcResult stolen = register(other, "REPORT", supervisorToken);
        assertThat(stolen.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(stolen.getResponse().getContentAsString())
                .path("error").asText()).isEqualTo("DOCUMENT_KIND_LOCKED");
        assertThat(kindOf(other, supervisorToken)).isNull();
    }

    @Test
    @DisplayName("B8: the Admin queue resolves the explicit kinds even when they contradict the order")
    void adminQueueResolvesExplicitKindsOverTheOrder() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("K5", supervisorUser, today.minusMonths(2), today.plusDays(2));
        UUID older = createDeliverable(intern, "Premier envoi", null);
        UUID newer = createDeliverable(intern, "Second envoi", null);
        submitInsideWindow(older, intern);
        submitInsideWindow(newer, intern);

        // Deliberately the opposite of assumption #18: the OLDEST is registered
        // as the JOURNAL and the NEWEST as the REPORT.
        assertThat(register(newer, "REPORT", supervisorToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(register(older, "JOURNAL", supervisorToken).getResponse().getStatus()).isEqualTo(200);

        MvcResult detail = getJson("/api/internship-validation/" + intern.internshipId(), adminToken);
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertThat(body.path("report").path("deliverableId").asText()).isEqualTo(newer.toString());
        assertThat(body.path("journal").path("deliverableId").asText()).isEqualTo(older.toString());
    }

    @Test
    @DisplayName("unmarked internships keep the documented order fallback (report = oldest, journal = newest)")
    void unmarkedInternshipsKeepTheOrderFallback() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("K6", supervisorUser, today.minusMonths(2), today.plusDays(2));
        UUID older = createDeliverable(intern, "Rapport (ancien)", null);
        UUID newer = createDeliverable(intern, "Journal (recent)", null);
        submitInsideWindow(older, intern);
        submitInsideWindow(newer, intern);

        MvcResult detail = getJson("/api/internship-validation/" + intern.internshipId(), adminToken);
        JsonNode body = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertThat(body.path("report").path("deliverableId").asText()).isEqualTo(older.toString());
        assertThat(body.path("journal").path("deliverableId").asText()).isEqualTo(newer.toString());
    }

    @Test
    @DisplayName("SU-VAL-01 scope: students and foreign supervisors cannot register; a blank kind is a coded 422")
    void registrationScopeIsEnforced() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern mine = createStudent("K7", supervisorUser, today.minusMonths(2), today.plusDays(2));
        UUID deliverableId = createDeliverable(mine, "Document", null);
        submitInsideWindow(deliverableId, mine);

        // The student's own document: the registration is the supervisor's action.
        assertThat(register(deliverableId, "REPORT", mine.token()).getResponse().getStatus()).isEqualTo(403);
        assertThat(register(deliverableId, "REPORT", otherSupervisorToken).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(register(deliverableId, "REPORT", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(register(UUID.randomUUID(), "REPORT", supervisorToken).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(register(UUID.randomUUID(), "REPORT", adminToken).getResponse().getStatus()).isEqualTo(404);

        MvcResult blank = register(deliverableId, null, supervisorToken);
        assertThat(blank.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(blank.getResponse().getContentAsString())
                .path("error").asText()).isEqualTo("DOCUMENT_KIND_REQUIRED");

        MvcResult unknown = register(deliverableId, "CHAPITRE", supervisorToken);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(unknown.getResponse().getContentAsString())
                .path("error").asText()).isEqualTo("INVALID_DOCUMENT_KIND");

        assertThat(register(deliverableId, "REPORT", supervisorToken).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("SU-VAL-01: the chat attachment remembers its source document and kind")
    void chatAttachmentCarriesTheSourceDocument() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern intern = createStudent("K8", supervisorUser, today.minusMonths(2), today.plusDays(2));
        UUID deliverableId = createDeliverable(intern, "Journal de stage", null);
        submitInsideWindow(deliverableId, intern);
        assertThat(register(deliverableId, "JOURNAL", supervisorToken).getResponse().getStatus()).isEqualTo(200);

        String conversationId = privateConversationOf(intern);
        MockMultipartFile file = new MockMultipartFile("file", "journal.pdf",
                "application/pdf", ("%PDF-1.4 T10 chat " + uid()).getBytes(StandardCharsets.UTF_8));
        MvcResult sent = mockMvc.perform(multipart("/api/conversations/" + conversationId
                        + "/messages/with-attachment")
                        .file(file)
                        .param("content", "Voici mon journal.")
                        .param("deliverableId", deliverableId.toString())
                        .header("Authorization", "Bearer " + intern.token()))
                .andReturn();
        assertThat(sent.getResponse().getStatus())
                .as("send must succeed: %s", sent.getResponse().getContentAsString())
                .isEqualTo(201);

        JsonNode attachment = objectMapper.readTree(sent.getResponse().getContentAsString())
                .path("attachments").get(0);
        assertThat(attachment.path("sourceDeliverableId").asText()).isEqualTo(deliverableId.toString());
        assertThat(attachment.path("sourceDocumentKind").asText()).isEqualTo("JOURNAL");

        // Ordinary chat files stay unlinked.
        MvcResult plain = mockMvc.perform(multipart("/api/conversations/" + conversationId
                        + "/messages/with-attachment")
                        .file(new MockMultipartFile("file", "notes.pdf", "application/pdf",
                                ("%PDF-1.4 notes " + uid()).getBytes(StandardCharsets.UTF_8)))
                        .param("content", "Notes diverses.")
                        .header("Authorization", "Bearer " + intern.token()))
                .andReturn();
        assertThat(objectMapper.readTree(plain.getResponse().getContentAsString())
                .path("attachments").get(0).path("sourceDeliverableId").isNull()).isTrue();
    }

    @Test
    @DisplayName("SU-VAL-01 scope: a document from another internship cannot be attached")
    void foreignDocumentCannotBeAttached() throws Exception {
        LocalDate today = LocalDate.now(TUNIS);
        Intern mine = createStudent("K9a", supervisorUser, today.minusMonths(2), today.plusDays(2));
        Intern foreign = createStudent("K9b", otherSupervisorUser, today.minusMonths(2), today.plusDays(2));
        UUID foreignDocument = createDeliverable(foreign, "Document etranger", null);
        String conversationId = privateConversationOf(mine);

        // A supervisor who does not supervise the document's internship: 403.
        MvcResult denied = sendAttachment(conversationId, foreignDocument, supervisorToken);
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);

        // The document's own supervisor is a member of BOTH the thread and the
        // document's internship — so the permission layer passes and only the
        // internship binding can refuse: coded 422, not 403.
        Intern second = createStudent("K9c", supervisorUser, today.minusMonths(2), today.plusDays(2));
        UUID secondDocument = createDeliverable(second, "Document d'un autre stage", null);
        MvcResult mismatch = sendAttachment(conversationId, secondDocument, supervisorToken);
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(mismatch.getResponse().getContentAsString())
                .path("error").asText()).isEqualTo("DELIVERABLE_NOT_IN_CONVERSATION");
    }

    private MvcResult sendAttachment(String conversationId, UUID deliverableId, String token)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "document.pdf",
                "application/pdf", ("%PDF-1.4 T10 scope " + uid()).getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/api/conversations/" + conversationId
                        + "/messages/with-attachment")
                        .file(file)
                        .param("content", "Document joint.")
                        .param("deliverableId", deliverableId.toString())
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }

    /** The internship's PRIVATE thread, discovered through the app's own list call. */
    private String privateConversationOf(Intern intern) throws Exception {
        // The fixture creates internships without running assign(), so make the
        // SAME thread the assignment flow auto-creates (best-effort there too).
        Internship internship = internshipRepository.findById(intern.internshipId()).orElseThrow();
        messagingService.ensurePrivateThread(internship, intern.user(), supervisorUser);

        MvcResult listed = getJson("/api/conversations", intern.token());
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        JsonNode rows = objectMapper.readTree(listed.getResponse().getContentAsString());
        for (JsonNode row : rows) {
            if (row.path("internshipId").asText().equals(intern.internshipId().toString())) {
                return row.path("id").asText();
            }
        }
        throw new IllegalStateException("No private conversation for " + intern.internshipId());
    }
}
