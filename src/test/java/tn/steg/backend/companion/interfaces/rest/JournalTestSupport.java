package tn.steg.backend.companion.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.ai.domain.client.AiCompletionClient;
import tn.steg.backend.ai.domain.client.AiCompletionResult;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.domain.service.SpecPdfTextExtractor;
import tn.steg.backend.companion.infrastructure.persistence.DeliverableRepository;
import tn.steg.backend.companion.infrastructure.persistence.DeliverableVersionRepository;
import tn.steg.backend.companion.infrastructure.persistence.TaskRepository;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipLifecycleService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T09/B5+B6 fixture shared by the four journal test classes: real rows
 * (Testcontainers), real JWTs, a scripted AI client, and helpers that read the
 * generated artifact through the SAME download endpoint the app uses.
 */
abstract class JournalTestSupport {

    @Autowired protected WebApplicationContext context;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected AiCompletionClient fakeAi;
    @Autowired protected SpecPdfTextExtractor pdfTextExtractor;
    @Autowired protected UserRepository userRepository;
    @Autowired protected RoleRepository roleRepository;
    @Autowired protected CandidateRepository candidateRepository;
    @Autowired protected UniversityRepository universityRepository;
    @Autowired protected DepartmentRepository departmentRepository;
    @Autowired protected EmployeeRepository employeeRepository;
    @Autowired protected InternshipService internshipService;
    @Autowired protected InternshipLifecycleService internshipLifecycleService;
    @Autowired protected InternshipRepository internshipRepository;
    @Autowired protected DeliverableRepository deliverableRepository;
    @Autowired protected DeliverableVersionRepository deliverableVersionRepository;
    @Autowired protected TaskRepository taskRepository;
    @Autowired protected JwtService jwtService;

    protected MockMvc mockMvc;
    protected String run;
    protected User adminUser;
    protected UserPrincipal adminPrincipal;
    protected String adminToken;
    protected User supervisorUser;
    protected String supervisorToken;
    protected User otherSupervisorUser;
    protected String otherSupervisorToken;
    protected Department department;
    protected University university;

    /** One student: his user row, JWT, internship id and reference. */
    protected record Intern(User user, String token, UUID internshipId, String reference) {
    }

    @BeforeEach
    void setUpJournalSupport() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.reset(fakeAi);
        when(fakeAi.getModel()).thenReturn("fake-model");
        when(fakeAi.getProvider()).thenReturn("gemini");
        run = uid();

        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();

        adminUser = userRepository.saveAndFlush(
                new User("adm_jrn_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        supervisorUser = userRepository.saveAndFlush(
                new User("sup_jrn_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorUser.getAssignedRoles().add(supervisorRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(
                supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        otherSupervisorUser = userRepository.saveAndFlush(
                new User("sup2_jrn_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        otherSupervisorUser.getAssignedRoles().add(supervisorRole);
        otherSupervisorUser = userRepository.saveAndFlush(otherSupervisorUser);
        otherSupervisorToken = jwtService.generateAccessToken(
                otherSupervisorUser.getId(), otherSupervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));

        department = departmentRepository.saveAndFlush(new Department("DIR_JRN_" + run, "Journal Dept", "JR"));
        linkEmployee(supervisorUser);
        linkEmployee(otherSupervisorUser);
        linkEmployee(adminUser);

        university = universityRepository.saveAndFlush(new University("UNI_JRN_" + run, "Journal Uni"));
    }

    protected static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void linkEmployee(User user) {
        Employee employee = new Employee("EMP-JRN-" + uid(), "Journal", "Emp", department);
        employee.setUser(user);
        employeeRepository.saveAndFlush(employee);
    }

    /** One student + his internship over the given period (server data). */
    protected Intern createStudent(String tag, User supervisor, LocalDate start, LocalDate end) {
        User internUser = userRepository.saveAndFlush(
                new User("cand_jrn_" + tag + "_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        Candidate candidate = new Candidate("Journal" + tag, "Student", internUser.getEmail(),
                sha256Base64(tag + run), university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse response = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), start, end, "Stage " + tag, "Technicien", false,
                supervisor.getId()), adminPrincipal);
        Internship internship = internshipRepository.findById(response.id()).orElseThrow();
        return new Intern(internUser,
                jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(),
                        List.of("ROLE_INTERN", "ROLE_CANDIDATE")),
                internship.getId(), internship.getReference());
    }

    protected static String sha256Base64(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    protected Task addTask(UUID internshipId, User creator, String title, TaskStatus status, LocalDate dueDate) {
        Internship internship = internshipRepository.findById(internshipId).orElseThrow();
        Task task = new Task(internship, creator, title, "Description of " + title);
        task.setStatus(status);
        task.setDueDate(dueDate);
        return taskRepository.saveTask(task);
    }

    // -------------------------------------------------------------------------
    // Scripted AI
    // -------------------------------------------------------------------------

    protected void scriptAi(String json) {
        when(fakeAi.complete(any(), anyList()))
                .thenReturn(AiCompletionResult.success(json, "fake-model", "gemini"));
    }

    protected void scriptAiUnavailable() {
        when(fakeAi.complete(any(), anyList()))
                .thenReturn(AiCompletionResult.failure("quota exceeded", "fake-model", "gemini"));
    }

    /** A schema-valid narrative; callers override the pieces they probe. */
    protected static String narrativeJson(String title, String periodStart, String periodEnd,
                                          String introduction, String conclusion) {
        return "{\"title\": \"" + title + "\","
                + "\"period\": {\"start\": \"" + periodStart + "\", \"end\": \"" + periodEnd + "\"},"
                + "\"introduction\": \"" + introduction + "\","
                + "\"summaryByPhase\": [{\"label\": \"Phase 1 — Analyse\", \"text\": \"Analyse des besoins.\"},"
                + "{\"label\": \"Phase 2 — Mise en oeuvre\", \"text\": \"Mise en oeuvre du plan.\"}],"
                + "\"taskTable\": [{\"title\": \"TASK_TITLE_PLACEHOLDER\", \"status\": \"APPROVED\","
                + " \"period\": \"S1\", \"outcome\": \"Livré et validé.\"}],"
                + "\"conclusion\": \"" + conclusion + "\"}";
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    protected MvcResult postJson(String path, String token, String body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.content(body);
        }
        return mockMvc.perform(request).andReturn();
    }

    protected MvcResult getJson(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    /**
     * Whitespace-flattened view of an extracted PDF text: table cells wrap, and
     * the back-office verifier normalizes whitespace before matching, so the
     * tests assert exactly what that check would see.
     */
    protected static String flat(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /** Reads the stored journal through the real download endpoint. */
    protected String downloadPdfText(UUID deliverableId, String token) throws Exception {
        MvcResult result = getJson("/api/internships/deliverables/" + deliverableId + "/download", token);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, StandardCharsets.UTF_8)).isEqualTo("%PDF-");
        return pdfTextExtractor.extractText(bytes, "journal.pdf");
    }
}
