package tn.steg.backend.notification.interfaces.ws;

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
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.notification.application.dto.NotificationPayload;

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
 * Pre-check (d) — STOMP DELIVERY OVER A REAL WEBSOCKET (AGENTS.md §8.1).
 *
 * <p>{@code NotificationCenterIntegrationTest} proves recipient ROUTING with a
 * recording notifier fake. This test closes the remaining gap: a REAL
 * {@link WebSocketStompClient} against the running app on a RANDOM_PORT,
 * authenticated with JWTs, subscribed to the personal
 * {@code /user/queue/notifications} destination — proving that:
 *
 * <ul>
 *   <li>the assigned supervisor receives the live {@link NotificationPayload}
 *       pushed during a status transition of <b>their</b> internship;</li>
 *   <li>an out-of-scope supervisor connected to the same broker at the same
 *       time receives <b>nothing</b> for that event;</li>
 *   <li>the out-of-scope supervisor's pipe is genuinely live (their own
 *       internship's transition does arrive) — so the empty inbox above is
 *       isolation, not a dead socket.</li>
 * </ul>
 *
 * <p>Push path under test: {@code InternshipLifecycleService} →
 * {@code InternshipStatusChangedEvent} (BEFORE_COMMIT, synchronous) →
 * {@code NotificationService.dispatchOnce} → {@code StompRealtimeNotifier}
 * → {@code convertAndSendToUser(email, "/queue/notifications", …)} → simple
 * broker → the subscribed session (user id resolves to the email because
 * {@code UserPrincipal.getName()} returns it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Pre-check (d) — real-WebSocket notification delivery reaches only the intended recipient")
class NotificationWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UniversityRepository universityRepository;
    @Autowired private CandidateRepository candidateRepository;
    @Autowired private InternshipService internshipService;
    @Autowired private JwtService jwtService;

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
    private final List<StompSession> sessions = new java.util.ArrayList<>();

    private String adminToken;
    private String supervisorAToken;
    private String supervisorBToken;
    private UUID internshipA;
    private UUID internshipB;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @BeforeEach
    void setUp() throws Exception {
        String suffix = uid();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role supervisorRole = roleRepository.findByCode("SUPERVISOR").orElseThrow();

        User adminUser = userRepository.saveAndFlush(
                new User("admin_wsnt_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        adminUser.getAssignedRoles().add(adminRole);
        adminUser = userRepository.saveAndFlush(adminUser);
        adminToken = jwtService.generateAccessToken(
                adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));
        UserPrincipal adminPrincipal =
                new UserPrincipal(adminUser.getId(), adminUser.getEmail(), List.of("ROLE_ADMIN"));

        User supervisorA = userRepository.saveAndFlush(
                new User("supA_wsnt_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorA.getAssignedRoles().add(supervisorRole);
        supervisorA = userRepository.saveAndFlush(supervisorA);
        supervisorAToken = jwtService.generateAccessToken(
                supervisorA.getId(), supervisorA.getEmail(), List.of("ROLE_SUPERVISOR"));

        User supervisorB = userRepository.saveAndFlush(
                new User("supB_wsnt_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE));
        supervisorB.getAssignedRoles().add(supervisorRole);
        supervisorB = userRepository.saveAndFlush(supervisorB);
        supervisorBToken = jwtService.generateAccessToken(
                supervisorB.getId(), supervisorB.getEmail(), List.of("ROLE_SUPERVISOR"));

        University university = universityRepository.saveAndFlush(
                new University("UNI_WSNT_" + suffix, "WS-NT University"));

        internshipA = createInternship("A" + suffix, university, supervisorA.getId(), adminPrincipal);
        internshipB = createInternship("B" + suffix, university, supervisorB.getId(), adminPrincipal);
    }

    private UUID createInternship(String tag, University university,
                                  UUID supervisorUserId, UserPrincipal adminPrincipal) throws Exception {
        User internUser = userRepository.saveAndFlush(
                new User("intern_wsnt_" + tag + "@steg.tn", "hash", UserStatus.ACTIVE));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(
                digest.digest(("CIN-WSNT-" + tag).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("WsNt" + tag, "Intern", internUser.getEmail(), cinHash, university);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("CIN-WSNT-" + tag);
        candidate = candidateRepository.saveAndFlush(candidate);

        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                "WS-NT project " + tag, "Ingénieur", false, supervisorUserId), adminPrincipal);
        return created.id();
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
    @DisplayName("the assigned supervisor's real socket receives the push; an out-of-scope supervisor receives nothing")
    void pushReachesOnlyTheAssignedSupervisorOverARealSocket() throws Exception {
        StompSession sessionA = connect(supervisorAToken);
        StompSession sessionB = connect(supervisorBToken);
        sessions.add(sessionA);
        sessions.add(sessionB);

        BlockingQueue<JsonNode> inboxA = new ArrayBlockingQueue<>(10);
        BlockingQueue<JsonNode> inboxB = new ArrayBlockingQueue<>(10);
        sessionA.subscribe("/user/queue/notifications", frame(inboxA));
        sessionB.subscribe("/user/queue/notifications", frame(inboxB));
        // Give the SUBSCRIBE frames time to reach the broker before publishing.
        Thread.sleep(500);

        // 1. A's internship transitions → A is in scope, B is not.
        assertThat(restPost("/api/internships/" + internshipA + "/status-transitions",
                "{\"targetStatus\":\"IN_PROGRESS\",\"comment\":\"pre-check d\"}", adminToken))
                .isEqualTo(200);

        JsonNode receivedByA = inboxA.poll(10, TimeUnit.SECONDS);
        assertThat(receivedByA)
                .as("the assigned supervisor must receive the notification on the real socket")
                .isNotNull();
        assertThat(receivedByA.get("relatedEntityId").asText()).isEqualTo(internshipA.toString());
        assertThat(receivedByA.get("relatedEntityType").asText()).isEqualTo("Internship");
        assertThat(receivedByA.get("title").asText()).isNotBlank();
        assertThat(receivedByA.get("notificationId").asText()).isNotBlank();

        assertThat(inboxB.poll(3, TimeUnit.SECONDS))
                .as("an out-of-scope supervisor subscribed to the same destination must receive nothing")
                .isNull();

        // 2. B's own internship transitions → B's pipe is proven live (isolation,
        //    not a dead socket) and A — out of scope here — stays silent.
        assertThat(restPost("/api/internships/" + internshipB + "/status-transitions",
                "{\"targetStatus\":\"IN_PROGRESS\",\"comment\":\"pre-check d\"}", adminToken))
                .isEqualTo(200);

        JsonNode receivedByB = inboxB.poll(10, TimeUnit.SECONDS);
        assertThat(receivedByB)
                .as("the other supervisor's own event arrives on their real socket")
                .isNotNull();
        assertThat(receivedByB.get("relatedEntityId").asText()).isEqualTo(internshipB.toString());

        assertThat(inboxA.poll(2, TimeUnit.SECONDS))
                .as("the first supervisor must not receive the second internship's event")
                .isNull();
    }

    // ------------------------------------------------------------------
    // Real-WebSocket plumbing (same proven pattern as MessagingWebSocketIntegrationTest)
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        client.setMessageConverter(converter);
        return client;
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return stompClient().connectAsync(
                        "ws://localhost:" + port + "/ws?token=" + token,
                        new org.springframework.web.socket.WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
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

    private int restPost(String path, String json, String bearer) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
