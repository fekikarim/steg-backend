package tn.steg.backend.messaging.interfaces.ws;

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
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.infrastructure.persistence.UserRepository;
import tn.steg.backend.iam.infrastructure.security.JwtService;
import tn.steg.backend.internship.application.InternshipService;
import tn.steg.backend.internship.application.dto.InternshipAssignmentRequest;
import tn.steg.backend.internship.application.dto.InternshipCreateManualRequest;
import tn.steg.backend.internship.application.dto.InternshipResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.infrastructure.persistence.InternshipRepository;
import tn.steg.backend.messaging.application.MessagingService;
import tn.steg.backend.messaging.application.dto.ConversationResponse;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.infrastructure.persistence.DepartmentRepository;
import tn.steg.backend.organization.infrastructure.persistence.EmployeeRepository;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase A9 WebSocket round-trip test: two authenticated sessions
 * (intern + supervisor) in the same private conversation exchange a message
 * over STOMP and both receive the broadcast on {@code /topic/conversations/{id}}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DisplayName("Messaging WebSocket STOMP Round-Trip Tests (A9)")
class MessagingWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

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
    private JwtService jwtService;

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

    private User internUser;
    private User supervisorUser;
    private User outsiderUser;
    private String internToken;
    private String supervisorToken;
    private String outsiderToken;
    private UUID conversationId;

    @org.springframework.beans.factory.annotation.Value("${steg.security.jwt.secret-key}")
    private String jwtSecret;

    @org.springframework.beans.factory.annotation.Value("${steg.security.jwt.issuer}")
    private String jwtIssuer;

    private final List<StompSession> sessions = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User hrUser = userRepository.saveAndFlush(new User("hr_ws_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        supervisorUser = userRepository.saveAndFlush(new User("sup_ws_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        internUser = userRepository.saveAndFlush(new User("intern_ws_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));

        supervisorToken = jwtService.generateAccessToken(supervisorUser.getId(), supervisorUser.getEmail(), List.of("ROLE_SUPERVISOR"));
        internToken = jwtService.generateAccessToken(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN", "ROLE_CANDIDATE"));
        outsiderUser = userRepository.saveAndFlush(new User("outsider_ws_" + suffix + "@steg.com", "hash", UserStatus.ACTIVE));
        outsiderToken = jwtService.generateAccessToken(outsiderUser.getId(), outsiderUser.getEmail(), List.of("ROLE_CANDIDATE"));

        Department dept = departmentRepository.saveAndFlush(new Department("DIR_WS_" + suffix, "Direction WS", "WS"));
        Employee supervisorEmployee = new Employee("EMP-WS-" + suffix, "Ws", "Supervisor", dept);
        supervisorEmployee.setUser(supervisorUser);
        supervisorEmployee = employeeRepository.saveAndFlush(supervisorEmployee);

        University uni = universityRepository.saveAndFlush(new University("UNI_WS_" + suffix, "WS University"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String cinHash = Base64.getEncoder().encodeToString(digest.digest(("CIN" + suffix).getBytes(StandardCharsets.UTF_8)));
        Candidate candidate = new Candidate("Ws", "Intern", "intern_ws_" + suffix + "@steg.com", cinHash, uni);
        candidate.setUser(internUser);
        candidate.setNationalIdEncrypted("CIN" + suffix);
        candidate = candidateRepository.saveAndFlush(candidate);

        UserPrincipal hrPrincipal = new UserPrincipal(hrUser.getId(), hrUser.getEmail(), List.of("ROLE_HR"));
        InternshipResponse created = internshipService.createManual(new InternshipCreateManualRequest(
                candidate.getId(), LocalDate.now().minusDays(3), LocalDate.now().plusDays(60),
                "WS Project", "Ingénieur", false), hrPrincipal);
        Internship internship = ((tn.steg.backend.internship.domain.repository.InternshipRepository) internshipRepository)
                .findById(created.id()).orElseThrow();
        internshipService.assign(internship.getId(), new InternshipAssignmentRequest(
                dept.getId(), supervisorEmployee.getId(),
                LocalDate.now().minusDays(3), LocalDate.now().plusDays(60), "WS assignment"), hrPrincipal);

        UserPrincipal internPrincipal = new UserPrincipal(internUser.getId(), internUser.getEmail(), List.of("ROLE_INTERN"));
        List<ConversationResponse> mine = messagingService.listMyConversations(internPrincipal);
        assertThat(mine).isNotEmpty();
        conversationId = mine.get(0).id();
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
    @DisplayName("Intern SEND over STOMP is persisted and broadcast to both members")
    void stompSendReceiveRoundTrip() throws Exception {
        WebSocketStompClient internClient = stompClient();
        WebSocketStompClient supervisorClient = stompClient();

        BlockingQueue<JsonNode> internInbox = new ArrayBlockingQueue<>(10);
        BlockingQueue<JsonNode> supervisorInbox = new ArrayBlockingQueue<>(10);

        StompSession internSession = connect(internClient, internToken);
        StompSession supervisorSession = connect(supervisorClient, supervisorToken);
        sessions.add(internSession);
        sessions.add(supervisorSession);

        internSession.subscribe("/topic/conversations/" + conversationId, frame(internInbox));
        supervisorSession.subscribe("/topic/conversations/" + conversationId, frame(supervisorInbox));

        // Small pause so SUBSCRIBE frames reach the broker before SEND
        Thread.sleep(500);

        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination("/app/conversations/" + conversationId + "/send");
        internSession.send(sendHeaders, java.util.Map.of("content", "hello over websocket"));

        JsonNode receivedByIntern = internInbox.poll(10, TimeUnit.SECONDS);
        JsonNode receivedBySupervisor = supervisorInbox.poll(10, TimeUnit.SECONDS);

        assertThat(receivedByIntern).isNotNull();
        assertThat(receivedBySupervisor).isNotNull();
        assertThat(receivedByIntern.get("content").asText()).isEqualTo("hello over websocket");
        assertThat(receivedBySupervisor.get("content").asText()).isEqualTo("hello over websocket");
        assertThat(receivedBySupervisor.get("sequenceNumber").asLong()).isEqualTo(1L);
        assertThat(receivedBySupervisor.get("status").asText()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("STOMP delivered/read acks broadcast status updates to both members")
    void stompAckBroadcastsStatusUpdates() throws Exception {
        BlockingQueue<JsonNode> internInbox = new ArrayBlockingQueue<>(20);
        BlockingQueue<JsonNode> supervisorInbox = new ArrayBlockingQueue<>(20);

        StompSession internSession = connect(stompClient(), internToken);
        StompSession supervisorSession = connect(stompClient(), supervisorToken);
        sessions.add(internSession);
        sessions.add(supervisorSession);

        internSession.subscribe("/topic/conversations/" + conversationId, frame(internInbox));
        supervisorSession.subscribe("/topic/conversations/" + conversationId, frame(supervisorInbox));
        Thread.sleep(500);

        // 1. Intern sends → both receive SENT
        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination("/app/conversations/" + conversationId + "/send");
        internSession.send(sendHeaders, java.util.Map.of("content", "ack lifecycle"));
        assertThat(internInbox.poll(10, TimeUnit.SECONDS).get("status").asText()).isEqualTo("SENT");
        assertThat(supervisorInbox.poll(10, TimeUnit.SECONDS).get("status").asText()).isEqualTo("SENT");

        // 2. Supervisor acks delivery over STOMP → both receive DELIVERED
        StompHeaders deliveredHeaders = new StompHeaders();
        deliveredHeaders.setDestination("/app/conversations/" + conversationId + "/delivered");
        supervisorSession.send(deliveredHeaders, java.util.Map.of("upToSequenceNumber", 1));
        JsonNode deliveredToIntern = internInbox.poll(10, TimeUnit.SECONDS);
        JsonNode deliveredToSupervisor = supervisorInbox.poll(10, TimeUnit.SECONDS);
        assertThat(deliveredToIntern).isNotNull();
        assertThat(deliveredToSupervisor).isNotNull();
        assertThat(deliveredToIntern.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(deliveredToSupervisor.get("status").asText()).isEqualTo("DELIVERED");

        // 3. Supervisor acks read over STOMP → both receive READ
        StompHeaders readHeaders = new StompHeaders();
        readHeaders.setDestination("/app/conversations/" + conversationId + "/read");
        supervisorSession.send(readHeaders, java.util.Map.of("upToSequenceNumber", 1));
        JsonNode readByIntern = internInbox.poll(10, TimeUnit.SECONDS);
        JsonNode readBySupervisor = supervisorInbox.poll(10, TimeUnit.SECONDS);
        assertThat(readByIntern).isNotNull();
        assertThat(readBySupervisor).isNotNull();
        assertThat(readByIntern.get("status").asText()).isEqualTo("READ");
        assertThat(readBySupervisor.get("status").asText()).isEqualTo("READ");
    }

    @Test
    @DisplayName("REST mutations broadcast to WebSocket subscribers (sender sees live status)")
    void restMutationsBroadcastToWsSubscribers() throws Exception {
        BlockingQueue<JsonNode> supervisorInbox = new ArrayBlockingQueue<>(20);
        StompSession supervisorSession = connect(stompClient(), supervisorToken);
        sessions.add(supervisorSession);
        supervisorSession.subscribe("/topic/conversations/" + conversationId, frame(supervisorInbox));
        Thread.sleep(500);

        // REST send → WS subscriber receives the SENT broadcast
        assertThat(restPost("/api/conversations/" + conversationId + "/messages",
                "{\"content\":\"rest to ws\"}", internToken)).isEqualTo(201);
        JsonNode broadcastSent = supervisorInbox.poll(10, TimeUnit.SECONDS);
        assertThat(broadcastSent).isNotNull();
        assertThat(broadcastSent.get("content").asText()).isEqualTo("rest to ws");
        assertThat(broadcastSent.get("status").asText()).isEqualTo("SENT");
        UUID messageId = UUID.fromString(broadcastSent.get("id").asText());

        // REST edit → WS subscriber receives the EDITED broadcast
        assertThat(restPatch("/api/conversations/messages/" + messageId,
                "{\"content\":\"rest to ws (edited)\"}", internToken)).isEqualTo(200);
        JsonNode broadcastEdited = supervisorInbox.poll(10, TimeUnit.SECONDS);
        assertThat(broadcastEdited).isNotNull();
        assertThat(broadcastEdited.get("status").asText()).isEqualTo("EDITED");
        assertThat(broadcastEdited.get("content").asText()).isEqualTo("rest to ws (edited)");

        // A fresh unedited message follows the full SENT → READ path live:
        // (the edited message above is terminal EDITED, so no READ broadcast
        // is emitted for it — terminal states are stable by design).
        assertThat(restPost("/api/conversations/" + conversationId + "/messages",
                "{\"content\":\"second\"}", internToken)).isEqualTo(201);
        assertThat(supervisorInbox.poll(10, TimeUnit.SECONDS).get("status").asText()).isEqualTo("SENT");
        assertThat(restPost("/api/conversations/" + conversationId + "/read",
                "{\"upToSequenceNumber\":2}", supervisorToken)).isEqualTo(204);
        JsonNode readBroadcast = supervisorInbox.poll(10, TimeUnit.SECONDS);
        assertThat(readBroadcast).isNotNull();
        assertThat(readBroadcast.get("status").asText()).isIn("DELIVERED", "READ");
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

    private int restPatch(String path, String json, String bearer) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("Deployment: handshake without a token is rejected")
    void handshakeWithoutTokenFails() {
        WebSocketStompClient client = stompClient();
        assertThatThrownBy(() -> client.connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new org.springframework.web.socket.WebSocketHttpHeaders(),
                        new StompHeaders(),
                        new StompSessionHandlerAdapter() {})
                .get(8, TimeUnit.SECONDS))
                .isNotNull();
    }

    @Test
    @DisplayName("Deployment: handshake with an expired token is rejected")
    void expiredTokenHandshakeFails() {
        javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys
                .hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String expired = io.jsonwebtoken.Jwts.builder()
                .subject(outsiderUser.getId().toString())
                .issuer(jwtIssuer)
                .issuedAt(Date.from(now.minus(30, ChronoUnit.MINUTES)))
                .expiration(Date.from(now.minus(15, ChronoUnit.MINUTES)))
                .claim("email", outsiderUser.getEmail())
                .claim("roles", List.of("ROLE_CANDIDATE"))
                .signWith(key)
                .compact();

        WebSocketStompClient client = stompClient();
        assertThatThrownBy(() -> client.connectAsync(
                        "ws://localhost:" + port + "/ws?token=" + expired,
                        new org.springframework.web.socket.WebSocketHttpHeaders(),
                        new StompHeaders(),
                        new StompSessionHandlerAdapter() {})
                .get(8, TimeUnit.SECONDS))
                .isNotNull();
    }

    @Test
    @DisplayName("Deployment: guessed topic SUBSCRIBE by a non-member delivers nothing")
    void nonMemberSubscribeDeliversNothing() throws Exception {
        BlockingQueue<JsonNode> supervisorInbox = new ArrayBlockingQueue<>(10);
        BlockingQueue<JsonNode> outsiderTopicInbox = new ArrayBlockingQueue<>(10);

        StompSession supervisorSession = connect(stompClient(), supervisorToken);
        StompSession outsiderSession = connect(stompClient(), outsiderToken);
        sessions.add(supervisorSession);
        sessions.add(outsiderSession);

        supervisorSession.subscribe("/topic/conversations/" + conversationId, frame(supervisorInbox));
        // Guessed topic subscription by a non-member: denied server-side (the
        // session may additionally be closed); either way nothing arrives.
        outsiderSession.subscribe("/topic/conversations/" + conversationId, frame(outsiderTopicInbox));
        Thread.sleep(500);

        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination("/app/conversations/" + conversationId + "/send");
        supervisorSession.send(sendHeaders, java.util.Map.of("content", "members only"));
        assertThat(supervisorInbox.poll(10, TimeUnit.SECONDS)).isNotNull();
        assertThat(outsiderTopicInbox.poll(3, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("Deployment: non-member SEND gets ACCESS_DENIED on the sender error queue only")
    void nonMemberSendYieldsSenderScopedError() throws Exception {
        BlockingQueue<JsonNode> supervisorInbox = new ArrayBlockingQueue<>(10);
        BlockingQueue<JsonNode> outsiderErrors = new ArrayBlockingQueue<>(10);

        StompSession supervisorSession = connect(stompClient(), supervisorToken);
        // Fresh session that only listens on its own error queue (never the topic)
        StompSession outsiderSession = connect(stompClient(), outsiderToken);
        sessions.add(supervisorSession);
        sessions.add(outsiderSession);

        supervisorSession.subscribe("/topic/conversations/" + conversationId, frame(supervisorInbox));
        outsiderSession.subscribe("/user/queue/errors", frame(outsiderErrors));
        Thread.sleep(500);

        StompHeaders intruderHeaders = new StompHeaders();
        intruderHeaders.setDestination("/app/conversations/" + conversationId + "/send");
        outsiderSession.send(intruderHeaders, java.util.Map.of("content", "intrude"));
        JsonNode error = outsiderErrors.poll(10, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("code").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(supervisorInbox.poll(3, TimeUnit.SECONDS)).isNull();
    }

    @SuppressWarnings("deprecation")
    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        client.setMessageConverter(converter);
        return client;
    }

    private StompSession connect(WebSocketStompClient client, String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        org.springframework.web.socket.WebSocketHttpHeaders httpHeaders =
                new org.springframework.web.socket.WebSocketHttpHeaders();
        return client.connectAsync(
                        "ws://localhost:" + port + "/ws?token=" + token,
                        httpHeaders, connectHeaders,
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
}
