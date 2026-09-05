package tn.steg.backend.messaging.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.messaging.application.dto.MessageResponse;
import tn.steg.backend.messaging.domain.repository.MessageRepository;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production gate: message ordering under true parallel sends.
 *
 * <p>Deliberately NOT {@code @Transactional}: fixtures are committed so all
 * sender threads observe them, and each send runs in its own transaction —
 * exactly like concurrent production requests. The pessimistic conversation
 * lock plus the {@code uq_messages_conv_seq} guard must yield a gapless,
 * duplicate-free 1..N sequence.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Messaging Concurrency Tests (A9 production gate)")
class MessagingConcurrencyTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private InternshipRepository internshipRepository;

    @Autowired
    private InternshipService internshipService;

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private MessageRepository messageRepository;

    private UserPrincipal internPrincipal;
    private UUID conversationId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User hrUser = userRepository.saveAndFlush(new User("hr_cc_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User supervisorUser = userRepository.saveAndFlush(new User("sup_cc_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        User internUser = userRepository.saveAndFlush(new User("intern_cc_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        internPrincipal = new UserPrincipal(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_CC_" + suffix, "CC Dept", "CC"));
        Employee supervisorEmployee = new Employee("EMP-CC-" + suffix, "Cc", "Supervisor", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_CC_" + suffix, "CC University"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("CIN" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Cc", "Intern", "intern_cc_" + suffix + "@steg.com", cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("CIN" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now().minusDays(3), LocalDate.now().plusDays(60),
                "CC Project", "Ingénieur", false), hrPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(3), LocalDate.now().plusDays(60), "CC assignment"), hrPrincipal);

        conversationId = messagingService.listMyConversations(internPrincipal).get(0).id();
    }

    @Test
    @DisplayName("20 parallel sends yield a gapless duplicate-free 1..20 sequence")
    void parallelSendsYieldGaplessSequence() throws Exception {
        int senders = 20;
        ExecutorService pool = Executors.newFixedThreadPool(senders);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<MessageResponse>> futures = new ArrayList<>();

        for (int i = 0; i < senders; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                startGate.await(10, TimeUnit.SECONDS);
                return messagingService.sendMessage(conversationId, "parallel-" + idx, internPrincipal);
            }));
        }
        startGate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<Long> sequences = new ArrayList<>();
        for (Future<MessageResponse> future : futures) {
            sequences.add(future.get(10, TimeUnit.SECONDS).sequenceNumber());
        }

        assertThat(sequences).hasSize(senders);
        assertThat(new java.util.HashSet<>(sequences)).hasSize(senders);
        assertThat(sequences.stream().sorted().toList())
                .containsExactlyElementsOf(
                        java.util.stream.LongStream.rangeClosed(1, senders).boxed().toList());

        Long max = messageRepository.findMaxSequenceNumber(conversationId).orElseThrow();
        assertThat(max).isEqualTo((long) senders);
    }
}
