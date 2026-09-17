package tn.steg.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.companion.domain.model.InternshipJournal;
import tn.steg.backend.companion.domain.model.JournalEntry;
import tn.steg.backend.companion.domain.model.JournalEntryStatus;
import tn.steg.backend.companion.infrastructure.persistence.InternshipJournalRepository;
import tn.steg.backend.companion.infrastructure.persistence.JournalEntryRepository;
import tn.steg.backend.document.domain.service.OfficialBrandingProvider;
import tn.steg.backend.document.domain.service.PdfDocumentRenderer;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.messaging.domain.model.Conversation;
import tn.steg.backend.messaging.domain.model.ConversationMember;
import tn.steg.backend.messaging.domain.model.ConversationMemberRole;
import tn.steg.backend.messaging.domain.model.ConversationType;
import tn.steg.backend.messaging.domain.repository.ConversationMemberRepository;
import tn.steg.backend.messaging.domain.repository.ConversationRepository;
import tn.steg.backend.messaging.domain.repository.MessageRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase E7 — XSS and Injection Safety Acceptance Tests.
 *
 * Verifies that user-controlled payloads (HTML tags, script tags, event handlers)
 * across messaging, companion journals, comments, filenames, candidate profiles,
 * and PDF generators are safely handled:
 * 1. REST endpoints return application/json; no HTML interpretation occurs.
 * 2. Stored payloads are persisted as exact data and never evaluated as executable code.
 * 3. File downloads enforce attachment disposition so browser does not execute HTML.
 * 4. PDFBox drawing pipeline shapes text into vector glyphs and does NOT embed script dictionaries.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("E7 — XSS & Content Injection Safety Probes")
class XssInjectionSafetyTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipJournalRepository journalRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationMemberRepository memberRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private PdfDocumentRenderer pdfRenderer;
    @Autowired private OfficialBrandingProvider brandingProvider;

    private MockMvc mockMvc;
    private User internUser;
    private String internToken;
    private Candidate candidate;
    private Internship internship;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        internUser = userRepository.saveAndFlush(new User("xss_intern@test.tn", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN", "ROLE_CANDIDATE"));

        University uni = universityRepository.saveAndFlush(new University("UNI-XSS", "XSS Test University"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest("99887766".getBytes(StandardCharsets.UTF_8)));
        candidate = new Candidate("Karim", "Test", "xss_intern@test.tn", hash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("99887766");
        candidate = candidateRepository.saveAndFlush(candidate);

        internship = new Internship();
        internship.setCandidate(candidate);
        internship.setReference("INT-XSS-" + UUID.randomUUID().toString().substring(0, 8));
        internship.setStartDate(LocalDate.now().minusDays(10));
        internship.setEndDate(LocalDate.now().plusDays(50));
        internship.setType(InternshipType.OBSERVATION);
        internship.setRequirement(InternshipRequirement.OBLIGATOIRE);
        internship.setSubject("Software Engineering");
        internship.setStatus(InternshipStatus.ACTIVE);
        internship = internshipRepository.saveAndFlush(internship);
    }

    @Test
    @DisplayName("1. Message content with script & event-handler payload is stored and returned as pure JSON string")
    void messagePayloadIsSafelyHandled() throws Exception {
        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.GROUP, "Direct", null));
        memberRepository.save(new ConversationMember(conversation, internUser, ConversationMemberRole.MEMBER));

        String injectionPayload = "<script>alert('xss')</script><img src=x onerror=alert(1)>";
        Map<String, Object> body = Map.of("content", injectionPayload);

        mockMvc.perform(post("/api/conversations/" + conversation.getId() + "/messages")
                        .header("Authorization", "Bearer " + internToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").value(injectionPayload));

        // Read history — payload is delivered in JSON array, browser does NOT render as HTML
        mockMvc.perform(get("/api/conversations/" + conversation.getId() + "/messages")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].content").value(injectionPayload));
    }

    @Test
    @DisplayName("2. Journal entry with SVG/onload payload is safely stored and serialized as JSON string")
    void journalEntryPayloadIsSafelyHandled() throws Exception {
        InternshipJournal journal = journalRepository.saveAndFlush(new InternshipJournal(internship));

        String injectionTitle = "<svg onload=alert(document.cookie)>";
        String injectionDesc = "<a href='javascript:alert(1)'>Click me</a>";

        JournalEntry entry = new JournalEntry(
                journal,
                internUser,
                injectionTitle,
                injectionDesc,
                LocalDate.now()
        );
        entry.setStatus(JournalEntryStatus.SUBMITTED);
        journalEntryRepository.saveAndFlush(entry);

        mockMvc.perform(get("/api/internships/" + internship.getId() + "/journal/entries")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].title").value(injectionTitle))
                .andExpect(jsonPath("$.content[0].description").value(injectionDesc));
    }

    @Test
    @DisplayName("3. Document upload with malicious script in original filename is stored safely and downloads as attachment")
    void documentUploadWithScriptFilenameIsSafe() throws Exception {
        byte[] validPdf = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>%%EOF\n".getBytes(StandardCharsets.UTF_8);
        String maliciousFilename = "\"><script>alert('filename')</script>.pdf";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                maliciousFilename,
                "application/pdf",
                validPdf
        );

        String responseJson = mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("type", "INTERNSHIP_APPLICATION")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.originalFileName").value(maliciousFilename))
                .andReturn().getResponse().getContentAsString();

        String docId = objectMapper.readTree(responseJson).get("id").asText();

        // Download: Content-Disposition must force attachment with quoted filename, preventing browser inline HTML execution
        mockMvc.perform(get("/api/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment;")));
    }

    @Test
    @DisplayName("4. Candidate profile with HTML payload in name is stored and serialized as JSON literal")
    void candidateProfileWithHtmlInNameIsSafe() throws Exception {
        String xssName = "<b onmouseover=alert(1)>TestName</b>";
        candidate.setFirstName(xssName);
        candidateRepository.saveAndFlush(candidate);

        mockMvc.perform(get("/api/candidates/" + candidate.getId())
                        .header("Authorization", "Bearer " + internToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.firstName").value(xssName));
    }

    @Test
    @DisplayName("5. PDF generator with script injection strings draws vector glyphs and does NOT embed PDF JavaScript actions")
    void pdfGenerationWithScriptPayloadProducesSafePdf() throws Exception {
        String xssTitle = "<script>window.location='https://evil.com'</script>";
        String xssParagraph = "<img src=x onerror=alert(1)> Official Certificate for intern.";

        byte[] logo = brandingProvider.getLogoPngBytes();
        byte[] pdfBytes = pdfRenderer.render(
                xssTitle,
                "Attestation de Stage",
                List.of(xssParagraph),
                "STEG Official Verification",
                logo
        );

        assertThat(pdfBytes).isNotEmpty();
        assertThat(new String(pdfBytes, 0, Math.min(pdfBytes.length, 10), StandardCharsets.US_ASCII))
                .startsWith("%PDF-");

        // Parse with Apache PDFBox: verify document structure has no executable JavaScript
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);

            // Document Catalog must have no OpenAction or embedded JavaScript
            assertThat(doc.getDocumentCatalog().getOpenAction())
                    .as("PDF must not contain OpenAction trigger")
                    .isNull();

            if (doc.getDocumentCatalog().getNames() != null) {
                assertThat(doc.getDocumentCatalog().getNames().getJavaScript())
                        .as("PDF Names dictionary must not contain JavaScript actions")
                        .isNull();
            }
        }
    }
}
