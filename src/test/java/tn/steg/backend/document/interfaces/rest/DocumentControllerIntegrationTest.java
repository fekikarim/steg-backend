package tn.steg.backend.document.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import tn.steg.backend.audit.infrastructure.persistence.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.candidate.domain.repository.UniversityRepository;
import tn.steg.backend.document.domain.model.Document;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.domain.repository.DocumentRepository;
import tn.steg.backend.document.infrastructure.persistence.FileAssetRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
@DisplayName("Document Controller and CIN Hard Protection Integration Tests")
class DocumentControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private FileAssetRepository fileAssetRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private User createTestUser(String email) {
        User user = new User(email, "hashed_password", UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private Candidate createTestCandidate(String email, String cin) throws Exception {
        User user = createTestUser(email);
        University uni = universityRepository.findAll().stream().findFirst()
                .orElseGet(() -> universityRepository.save(new University("UNI_DOC_1", "Test Doc Uni")));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));

        Candidate candidate = new Candidate("DocUser", "Test", email, hash, uni);
        candidate.setUser(user);
        candidate.setNationalIdEncrypted(cin);
        return candidateRepository.save(candidate);
    }

    @Test
    @DisplayName("Upload CV: sets restrictedAccess=false, generates DOC-YYYY-NNNNN reference, and saves metadata")
    void uploadCvSetsRestrictedAccessFalse() throws Exception {
        Candidate candidate = createTestCandidate("cand_cv@test.tn", "09876543");
        String token = jwtService.generateAccessToken(
                candidate.getUser().getId(),
                candidate.getEmail(),
                List.of("ROLE_CANDIDATE")
        );

        byte[] pdfContent = "%PDF-1.4 header CV file content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "my_cv.pdf",
                "application/pdf",
                pdfContent
        );

        mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("type", "CV")
                        .param("title", "Curriculum Vitae 2026")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(matchesPattern("^DOC-\\d{4}-\\d{5}$")))
                .andExpect(jsonPath("$.type").value("CV"))
                .andExpect(jsonPath("$.restrictedAccess").value(false))
                .andExpect(jsonPath("$.originalFileName").value("my_cv.pdf"))
                .andExpect(jsonPath("$.mimeType").value("application/pdf"));
    }

    @Test
    @DisplayName("Upload CIN_COPY: automatically sets restrictedAccess=true (CIN Hard Protection)")
    void uploadCinCopySetsRestrictedAccessTrue() throws Exception {
        Candidate candidate = createTestCandidate("cand_cin@test.tn", "12345678");
        String token = jwtService.generateAccessToken(
                candidate.getUser().getId(),
                candidate.getEmail(),
                List.of("ROLE_CANDIDATE")
        );

        // PNG signature bytes: 89 50 4E 47 0D 0A 1A 0A
        byte[] pngContent = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 1};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "cin.png",
                "image/png",
                pngContent
        );

        mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("type", "CIN_COPY")
                        .param("title", "Carte d'Identite Nationale")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CIN_COPY"))
                .andExpect(jsonPath("$.restrictedAccess").value(true));
    }

    @Test
    @DisplayName("CIN Hard Protection: Candidate cannot use regular download for restricted document")
    void cannotDownloadRestrictedDocViaRegularEndpoint() throws Exception {
        Candidate candidate = createTestCandidate("cand_cin2@test.tn", "87654321");
        String token = jwtService.generateAccessToken(
                candidate.getUser().getId(),
                candidate.getEmail(),
                List.of("ROLE_CANDIDATE")
        );

        byte[] pngContent = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 1};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "cin.png",
                "image/png",
                pngContent
        );

        String responseJson = mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("type", "CIN_COPY")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String docId = objectMapper.readTree(responseJson).get("id").asText();

        // Regular download endpoint must reject restricted document
        mockMvc.perform(get("/api/documents/" + docId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CIN Hard Protection: Restricted download requires DOCUMENT_VIEW_RESTRICTED or ADMIN and logs synchronously in AuditLog")
    void restrictedDownloadRequiresExplicitAuthorityAndLogsSynchronously() throws Exception {
        Candidate candidate = createTestCandidate("cand_cin3@test.tn", "44556677");
        String candidateToken = jwtService.generateAccessToken(
                candidate.getUser().getId(),
                candidate.getEmail(),
                List.of("ROLE_CANDIDATE")
        );

        byte[] pngContent = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 1};
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "cin.png",
                "image/png",
                pngContent
        );

        String responseJson = mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("type", "CIN_COPY")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String docId = objectMapper.readTree(responseJson).get("id").asText();

        // 1. Candidate without DOCUMENT_VIEW_RESTRICTED gets 403 Forbidden
        mockMvc.perform(get("/api/documents/" + docId + "/download-restricted")
                        .header("Authorization", "Bearer " + candidateToken))
                .andExpect(status().isForbidden());

        // 2. Staff user WITH DOCUMENT_VIEW_RESTRICTED can download and produces synchronous AuditLog
        User staffUser = createTestUser("hr_compliance@steg.tn");
        String staffToken = jwtService.generateAccessToken(
                staffUser.getId(),
                staffUser.getEmail(),
                List.of("ROLE_HR", "DOCUMENT_VIEW_RESTRICTED")
        );

        long auditCountBefore = auditLogRepository.count();

        mockMvc.perform(get("/api/documents/" + docId + "/download-restricted")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG_VALUE));

        long auditCountAfter = auditLogRepository.count();
        assertThat(auditCountAfter).isGreaterThan(auditCountBefore);

        boolean auditFound = auditLogRepository.findAll().stream()
                .anyMatch(log -> "DOCUMENT_ACCESSED_RESTRICTED".equals(log.getAction())
                        && log.getActor() != null
                        && log.getActor().getId().equals(staffUser.getId()));

        assertThat(auditFound).isTrue();
    }

    @Test
    @DisplayName("AI Safety Filter: findAllByRestrictedAccessFalse() excludes all CIN_COPY documents")
    void aiSafetyFilterExcludesRestrictedDocuments() throws Exception {
        Candidate candidate = createTestCandidate("cand_filter@test.tn", "99887766");
        String token = jwtService.generateAccessToken(
                candidate.getUser().getId(),
                candidate.getEmail(),
                List.of("ROLE_CANDIDATE")
        );

        // Upload normal CV
        byte[] pdfContent = "%PDF-1.4 header".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "cv.pdf", "application/pdf", pdfContent))
                        .param("type", "CV")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        // Upload restricted CIN_COPY
        byte[] pngContent = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "cin.png", "image/png", pngContent))
                        .param("type", "CIN_COPY")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        List<Document> safeDocs = documentRepository.findAllByRestrictedAccessFalse();
        assertThat(safeDocs).isNotEmpty();
        assertThat(safeDocs).noneMatch(d -> d.getType() == DocumentType.CIN_COPY);
        assertThat(safeDocs).noneMatch(Document::getRestrictedAccess);
    }
}
