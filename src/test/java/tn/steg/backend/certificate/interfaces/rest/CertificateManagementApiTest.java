package tn.steg.backend.certificate.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.support.InternshipLifecycleFixture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S8 certificate management (AGENTS.md §5.7, Testcontainers): Admin-only
 * CRUD, PDF content per §5.7, VALIDATED eligibility both ways, versioned
 * regeneration, soft-delete audit, Supervisor 403s, concurrent unique
 * references.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("S8 — certificate management API (Testcontainers)")
class CertificateManagementApiTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private InternshipRepository internshipRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private InternshipLifecycleService lifecycleService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private User supervisorUser;
    private String supervisorToken;
    private University university;
    private Department department;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();

        adminUser = userRepository.saveAndFlush(new User("admin_cert_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = userRepository.saveAndFlush(new User("sup_cert_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(supervisorRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(
                supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        department = departmentRepository.saveAndFlush(new Department("DIR_CERT_" + uid(), "Cert Dept", "CERT"));
        Employee adminEmployee = new Employee("EMP-CERT-ADM-" + uid(), "Admin", "Cert", department);
        adminEmployee.setUser(adminUser);
        employeeRepository.saveAndFlush(adminEmployee);

        university = universityRepository.saveAndFlush(new University("UNI_CERT_" + uid(), "Cert Uni"));
    }

    private tn.steg.backend.internship.domain.repository.InternshipRepository port() {
        return internshipRepository;
    }

    private Internship validatedInternship(String tag) throws Exception {
        User internUser = userRepository.saveAndFlush(
                new User("cand_cert_" + tag + "_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("First" + tag, "Last" + tag, internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);
        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "Certificate project", "Ingénieur", false, adminUser.getId()), adminPrincipal);
        InternshipLifecycleFixture.startAndValidate(
                lifecycleService,
                (tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository,
                resp.id(), adminPrincipal);
        return port().findById(resp.id()).orElseThrow();
    }

    private UUID generate(UUID internshipId, String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/internships/" + internshipId + "/certificates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    @DisplayName("PDF text contains every §5.7 required field")
    void pdfContainsRequiredFields() throws Exception {
        Internship internship = validatedInternship("pdffields");
        UUID certificateId = generate(internship.getId(), adminToken);

        MvcResult download = mockMvc.perform(get("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String text = pdfText(download.getResponse().getContentAsByteArray());

        MvcResult detail = mockMvc.perform(get("/api/certificates/" + certificateId + "/detail")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String reference = objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("certificate").path("reference").asText();

        assertThat(text).contains("Firstpdffields Lastpdffields");
        assertThat(text).contains("Cert Uni");
        assertThat(text).contains("01/01/2026");
        assertThat(text).contains("01/04/2026");
        assertThat(text).contains(reference);
        assertThat(text).contains("STEG");
        assertThat(text).contains("servir et valoir");
        assertThat(text).contains("Le Directeur des Ressources Humaines");
        assertThat(reference).startsWith("CERT-");
    }

    @Test
    @DisplayName("eligibility both ways: non-VALIDATED → 409, VALIDATED → 201")
    void eligibilityBothWays() throws Exception {
        Internship internship = validatedInternship("elig");
        // Force back below VALIDATED through the public surface is impossible;
        // use a fresh IN_PROGRESS internship for the refusal probe.
        User internUser = userRepository.saveAndFlush(
                new User("cand_elig2_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(
                digest.digest(("elig2" + uid()).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Elig", "Two", internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);
        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "Elig project", "Ingénieur", false, adminUser.getId()), adminPrincipal);
        mockMvc.perform(post("/api/internships/" + resp.id() + "/certificates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INTERNSHIP_NOT_VALIDATED"));

        generate(internship.getId(), adminToken);
    }

    @Test
    @DisplayName("edit regenerates with a new version; history is kept; reference stays stable")
    void editRegeneratesNewVersion() throws Exception {
        Internship internship = validatedInternship("versioned");
        UUID certificateId = generate(internship.getId(), adminToken);

        MvcResult regenerated = mockMvc.perform(put("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueDate\":\"2026-05-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificate.versionNumber").value(2))
                .andReturn();
        String reference = objectMapper.readTree(regenerated.getResponse().getContentAsString())
                .path("certificate").path("reference").asText();

        MvcResult detail = mockMvc.perform(get("/api/certificates/" + certificateId + "/detail")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificate.reference").value(reference))
                .andExpect(jsonPath("$.certificate.versionNumber").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].versionNumber").value(1))
                .andExpect(jsonPath("$.versions[1].versionNumber").value(2))
                .andReturn();

        // The old version still downloads (history, not overwrite).
        MvcResult v1 = mockMvc.perform(
                        get("/api/certificates/" + certificateId + "/versions/1/download")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(pdfText(v1.getResponse().getContentAsByteArray())).contains(reference);
    }

    @Test
    @DisplayName("soft delete revokes with an audit entry; regenerating afterwards starts anew")
    void deleteAuditsAndAllowsRegeneration() throws Exception {
        Internship internship = validatedInternship("revoked");
        UUID certificateId = generate(internship.getId(), adminToken);

        mockMvc.perform(delete("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtAsc("Certificate", certificateId)
                .stream().map(r -> r.getAction()))
                .contains("CERTIFICATE_REVOKED");

        // Second delete is an explicit conflict, not a silent no-op.
        mockMvc.perform(delete("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CERTIFICATE_ALREADY_REVOKED"));

        // A fresh certificate can be generated after the revocation.
        MvcResult again = mockMvc.perform(post("/api/internships/" + internship.getId() + "/certificates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(objectMapper.readTree(again.getResponse().getContentAsString())
                .get("reference").asText()).startsWith("CERT-");
    }

    @Test
    @DisplayName("Supervisor gets 403 on generate, list, detail, regenerate and delete")
    void supervisorIsForbidden() throws Exception {
        Internship internship = validatedInternship("supforbid");
        UUID certificateId = generate(internship.getId(), adminToken);

        mockMvc.perform(post("/api/internships/" + internship.getId() + "/certificates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/certificates")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/certificates/" + certificateId + "/detail")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + supervisorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/certificates/" + certificateId)
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("workspace list is paged, searchable and filterable server-side")
    void workspaceListIsPagedAndFiltered() throws Exception {
        // No @Transactional in this class: isolate through a run tag shared by
        // exactly the two fixtures below.
        String tag = uid();
        Internship a = validatedInternship("lista" + tag);
        Internship b = validatedInternship("listb" + tag);
        generate(a.getId(), adminToken);
        generate(b.getId(), adminToken);
        String allQ = tag;

        mockMvc.perform(get("/api/certificates?q=" + allQ + "&size=1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2));

        mockMvc.perform(get("/api/certificates?q=Firstlista" + tag)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].internshipId").value(a.getId().toString()));

        mockMvc.perform(get("/api/certificates?q=" + allQ + "&status=GENERATED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("concurrent generations yield unique reference numbers")
    void concurrentGenerationsAreUnique() throws Exception {
        int parallel = 4;
        List<Internship> internships = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            internships.add(validatedInternship("conc" + i + uid()));
        }
        var pool = Executors.newFixedThreadPool(parallel);
        try {
            CountDownLatch ready = new CountDownLatch(parallel);
            CountDownLatch go = new CountDownLatch(1);
            ConcurrentLinkedQueue<String> references = new ConcurrentLinkedQueue<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Future<?>> futures = new ArrayList<>();
            for (Internship internship : internships) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!go.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("latch timeout");
                        }
                        MvcResult result = mockMvc.perform(
                                        post("/api/internships/" + internship.getId() + "/certificates")
                                                .header("Authorization", "Bearer " + adminToken))
                                .andExpect(status().isCreated())
                                .andReturn();
                        references.add(objectMapper.readTree(result.getResponse().getContentAsString())
                                .get("reference").asText());
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            assertThat(failure.get()).isNull();
            assertThat(references).hasSize(parallel);
            assertThat(Set.copyOf(references)).hasSize(parallel);
            references.forEach(reference -> assertThat(reference).startsWith("CERT-"));
        } finally {
            pool.shutdownNow();
        }
    }
}
