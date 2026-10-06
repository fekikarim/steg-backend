package tn.steg.backend.community.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.infrastructure.persistence.CandidateRepository;
import tn.steg.backend.candidate.infrastructure.persistence.UniversityRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T08 — community realtime: eligible subscribers receive feed envelopes on
 * {@code /topic/community}; ineligible subscribers receive nothing
 * (Testcontainers + real WebSocket, RANDOM_PORT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("T08 — Community Realtime Tests")
class CommunityRealtimeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private InternshipService internshipService;

    @Autowired
    private JwtService jwtService;

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
    private final List<StompSession> sessions = new java.util.ArrayList<>();

    private String internToken;
    private String intern2Token;
    private String outsiderToken;
    private String supervisorToken;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User supervisorUser = new User("t08r_sup_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE);
        Role supRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();
        supervisorUser.getAssignedRoles().add(supRole);
        supervisorUser = userRepository.saveAndFlush(supervisorUser);
        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(),
                List.of("ROLE_SUPERVISOR"));

        User internUser = userRepository.saveAndFlush(new User("t08r_intern_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        User intern2User = userRepository.saveAndFlush(new User("t08r_intern2_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        intern2Token = jwtService.generateAccessToken(intern2User.getId(), intern2User.getEmail(),
                List.of("ROLE_CANDIDATE", "ROLE_INTERN"));

        User outsiderUser = userRepository.saveAndFlush(new User("t08r_outsider_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        outsiderToken = jwtService.generateAccessToken(outsiderUser.getId(), outsiderUser.getEmail(),
                List.of("ROLE_CANDIDATE"));

        User hrUser = userRepository.saveAndFlush(new User("t08r_hr_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_ADMIN"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_T08R_" + suffix, "Direction T08R", "T08R"));
        Employee supervisorEmployee = new Employee("EMP-T08R-" + suffix, "Rt", "Supervisor", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_T08R_" + suffix, "T08R University"));
        newActiveInternship("t08r_intern_" + suffix, internUser, uni, dept, supervisorEmployee, hrPrincipal, suffix + "31");
        newActiveInternship("t08r_intern2_" + suffix, intern2User, uni, dept, supervisorEmployee, hrPrincipal, suffix + "32");
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : sessions) {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
    }

    @Test
    @DisplayName("Post/comment/removal broadcasts reach eligible subscribers with kind+postId only")
    void broadcastsReachEligibleSubscribers() throws Exception {
        BlockingQueue<JsonNode> intern2Inbox = new ArrayBlockingQueue<>(10);
        BlockingQueue<JsonNode> supervisorInbox = new ArrayBlockingQueue<>(10);

        StompSession intern2Session = connect(stompClient(), intern2Token);
        StompSession supervisorSession = connect(stompClient(), supervisorToken);
        sessions.add(intern2Session);
        sessions.add(supervisorSession);

        intern2Session.subscribe("/topic/community", frame(intern2Inbox));
        supervisorSession.subscribe("/topic/community", frame(supervisorInbox));
        Thread.sleep(500);

        assertThat(restPost("/api/community/posts", "{\"body\":\"realtime hello\"}", internToken)).isEqualTo(201);
        JsonNode created = intern2Inbox.poll(10, TimeUnit.SECONDS);
        assertThat(created).isNotNull();
        assertThat(created.get("kind").asText()).isEqualTo("POST_CREATED");
        String postId = created.get("postId").asText();
        assertThat(created.has("body")).isFalse();
        assertThat(supervisorInbox.poll(10, TimeUnit.SECONDS).get("kind").asText()).isEqualTo("POST_CREATED");

        assertThat(restPost("/api/community/posts/" + postId + "/comments",
                "{\"body\":\"realtime reply\"}", intern2Token)).isEqualTo(201);
        JsonNode commented = intern2Inbox.poll(10, TimeUnit.SECONDS);
        assertThat(commented.get("kind").asText()).isEqualTo("COMMENT_ADDED");
        assertThat(commented.get("postId").asText()).isEqualTo(postId);
    }

    @Test
    @DisplayName("A user without community access receives nothing on the topic")
    void outsiderSubscriptionReceivesNothing() throws Exception {
        BlockingQueue<JsonNode> outsiderInbox = new ArrayBlockingQueue<>(10);
        StompSession outsiderSession = connect(stompClient(), outsiderToken);
        sessions.add(outsiderSession);

        outsiderSession.subscribe("/topic/community", frame(outsiderInbox));
        Thread.sleep(500);

        assertThat(restPost("/api/community/posts", "{\"body\":\"not for outsiders\"}", internToken)).isEqualTo(201);
        assertThat(outsiderInbox.poll(3, TimeUnit.SECONDS)).isNull();
    }

    private int restPost(String path, String json, String bearer) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void newActiveInternship(String emailPrefix, User intern, University uni,
                                     Department dept, Employee supervisor, UserPrincipal admin,
                                     String cin) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(cin.getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Rt", "Live", emailPrefix + "@steg.com", cinHash, uni);
        candidate.setUser(intern);
        candidate.setNationalIdEncrypted(cin);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(),
                LocalDate.now().minusDays(10),
                LocalDate.now().plusDays(60),
                "T08R Project", "Ingénieur", false), admin);
        internshipService.assign(created.id(), new InternshipAssignmentRequest(
                dept.getId(), supervisor.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60), "T08R assignment"), admin);
    }

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    private StompSession connect(WebSocketStompClient client, String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync(
                        "ws://localhost:" + port + "/ws?token=" + token,
                        new org.springframework.web.socket.WebSocketHttpHeaders(),
                        headers,
                        new StompSessionHandlerAdapter() {})
                .get(8, TimeUnit.SECONDS);
        assertThat(session.isConnected()).isTrue();
        return session;
    }

    private StompFrameHandler frame(BlockingQueue<JsonNode> inbox) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    byte[] bytes = (byte[]) payload;
                    inbox.offer(objectMapper.readTree(bytes), 5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
}
