package tn.steg.backend.finance.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tn.steg.backend.TestcontainersConfiguration;
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
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;
import tn.steg.backend.support.InternshipLifecycleFixture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 4 follow-up to legacy-removal #25: PAY-/FC-/DOC- references moved
 * from count-then-write to atomic database sequences (V51). Four concurrent
 * receipt generations for distinct VALIDATED internships must all succeed
 * with distinct, readable references — the old scheme died on the unique
 * constraint here (same failure mode #25 proved for CERT- with 3/4 409s).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Receipt/case/document sequence references stay unique under concurrency")
class ReceiptReferenceConcurrencyTest {

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
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private User adminUser;
    private String adminToken;
    private UserPrincipal adminPrincipal;
    private University university;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String run = uid();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        adminUser = userRepository.saveAndFlush(new User("admin_payseq_" + run + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        adminPrincipal = new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        Department department = departmentRepository.saveAndFlush(
                new Department("DIR-PAYSEQ-" + run, "Payseq Dept", "PQ"));
        Employee adminEmployee = new Employee("EMP-PAYSEQ-ADM-" + run, "Admin", "Payseq", department);
        adminEmployee.setUser(adminUser);
        employeeRepository.saveAndFlush(adminEmployee);

        university = universityRepository.saveAndFlush(new University("UNI-PAYSEQ-" + run, "Payseq Uni"));
    }

    private Internship validatedInternship(String tag) throws Exception {
        User internUser = userRepository.saveAndFlush(
                new User("cand_payseq_" + tag + "_" + uid() + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(tag.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("First" + tag, "Last" + tag, internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate = candidateRepository.saveAndFlush(candidate);
        InternshipResponse resp = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "Payseq project", "Ingénieur", false, adminUser.getId()), adminPrincipal);
        InternshipLifecycleFixture.startAndValidate(
                lifecycleService,
                (tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository,
                resp.id(), adminPrincipal);
        return ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(resp.id()).orElseThrow();
    }

    @Test
    @DisplayName("concurrent receipt generations yield distinct readable PAY-/FC- references")
    void concurrentReceiptsYieldDistinctReferences() throws Exception {
        int parallel = 4;
        List<Internship> internships = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            internships.add(validatedInternship("payseq" + i + uid()));
        }
        var pool = Executors.newFixedThreadPool(parallel);
        try {
            CountDownLatch ready = new CountDownLatch(parallel);
            CountDownLatch go = new CountDownLatch(1);
            ConcurrentLinkedQueue<String> payRefs = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<String> caseIds = new ConcurrentLinkedQueue<>();
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
                                        post("/api/internship-validation/" + internship.getId() + "/receipt")
                                                .header("Authorization", "Bearer " + adminToken))
                                .andExpect(status().isOk())
                                .andReturn();
                        var body = objectMapper.readTree(result.getResponse().getContentAsString());
                        payRefs.add(body.get("reference").asText());
                        caseIds.add(body.get("financeCaseId").asText());
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
            assertThat(payRefs).hasSize(parallel);
            assertThat(Set.copyOf(payRefs)).hasSize(parallel);
            payRefs.forEach(reference -> assertThat(reference).matches("PAY-\\d{4}-\\d{5}"));
            // One finance case per internship: FC- references are distinct too.
            assertThat(Set.copyOf(caseIds)).hasSize(parallel);
        } finally {
            pool.shutdownNow();
        }
    }
}
