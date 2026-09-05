package tn.steg.backend.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.messaging.application.dto.AddMemberRequest;
import tn.steg.backend.messaging.application.dto.ConversationResponse;
import tn.steg.backend.messaging.application.dto.CreateGroupRequest;
import tn.steg.backend.messaging.application.dto.MarkDeliveredRequest;
import tn.steg.backend.messaging.application.dto.MarkReadRequest;
import tn.steg.backend.messaging.application.dto.MessageResponse;
import tn.steg.backend.messaging.application.dto.SendMessageRequest;
import tn.steg.backend.messaging.application.dto.UnreadCountResponse;
import tn.steg.backend.messaging.domain.model.Conversation;
import tn.steg.backend.messaging.domain.model.ConversationMemberRole;
import tn.steg.backend.messaging.domain.model.ConversationType;
import tn.steg.backend.messaging.domain.model.Message;
import tn.steg.backend.messaging.domain.model.MessageStatus;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production gate: client-facing WebSocket/REST payload contracts.
 * Any rename, removal or addition of a wire field breaks these tests on
 * purpose — update {@code PHASE_A9_WS_CONTRACTS.md} alongside the code.
 */
@DisplayName("Messaging Payload Contract Tests (production gate)")
class MessagingContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            keys.add(fields.next());
        }
        return keys;
    }

    private Message sampleMessage(MessageStatus status) {
        User sender = new User("sender@steg.com", "hash", UserStatus.ACTIVE);
        sender.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        Conversation conversation = new Conversation(ConversationType.PRIVATE, "Thread", null);
        conversation.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        conversation.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        conversation.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        Message message = new Message(conversation, sender, "hello", 7L);
        message.setId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        message.setStatus(status);
        message.setSentAt(Instant.parse("2026-01-02T00:00:00Z"));
        return message;
    }

    @Test
    @DisplayName("Message broadcast payload has the exact contracted fields")
    void messagePayloadContract() throws Exception {
        MessageResponse.AttachmentResponse attachment = new MessageResponse.AttachmentResponse(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                "report.pdf", "application/pdf", 1234L);
        JsonNode node = objectMapper.valueToTree(MessageResponse.from(sampleMessage(MessageStatus.READ), List.of(attachment)));

        assertThat(keys(node)).containsExactly("attachments", "content", "conversationId", "deletedAt",
                "editedAt", "id", "senderId", "sentAt", "sequenceNumber", "status");
        assertThat(node.get("content").asText()).isEqualTo("hello");
        assertThat(node.get("status").asText()).isEqualTo("READ");
        assertThat(node.get("sequenceNumber").asLong()).isEqualTo(7L);
        assertThat(keys(node.get("attachments").get(0)))
                .containsExactly("fileAssetId", "fileName", "id", "mimeType", "size");
    }

    @Test
    @DisplayName("Deleted messages keep shape but redact content and attachments")
    void deletedMessageRedactionContract() throws Exception {
        Message message = sampleMessage(MessageStatus.SENT);
        message.setDeletedAt(Instant.parse("2026-01-03T00:00:00Z"));
        message.setStatus(MessageStatus.DELETED);

        JsonNode node = objectMapper.valueToTree(MessageResponse.from(message, List.of(
                new MessageResponse.AttachmentResponse(UUID.randomUUID(), UUID.randomUUID(), "x.pdf", "application/pdf", 1L))));

        assertThat(node.get("content").asText()).isEqualTo("[message deleted]");
        assertThat(node.get("attachments").size()).isEqualTo(0);
        assertThat(node.get("status").asText()).isEqualTo("DELETED");
        assertThat(node.get("sequenceNumber").asLong()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Conversation payload has the exact contracted fields incl. watermarks")
    void conversationPayloadContract() throws Exception {
        Conversation conversation = new Conversation(ConversationType.GROUP, "Interns", null);
        conversation.setId(UUID.fromString("66666666-6666-6666-6666-666666666666"));
        conversation.setArchived(false);
        conversation.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        conversation.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        ConversationResponse.MemberResponse member = new ConversationResponse.MemberResponse(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                ConversationMemberRole.MODERATOR.name(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"), 41L, 42L);

        JsonNode node = objectMapper.valueToTree(
                ConversationResponse.from(conversation, List.of(member), 42L, 3L));

        assertThat(keys(node)).containsExactly("archived", "createdAt", "id", "internshipId",
                "lastSequenceNumber", "members", "title", "type", "unreadCount", "updatedAt");
        assertThat(node.get("type").asText()).isEqualTo("GROUP");
        assertThat(node.get("unreadCount").asLong()).isEqualTo(3L);
        assertThat(keys(node.get("members").get(0))).containsExactly("joinedAt", "lastDeliveredSequenceNumber",
                "lastReadAt", "lastReadSequenceNumber", "role", "userId");
        assertThat(node.get("members").get(0).get("lastReadSequenceNumber").asLong()).isEqualTo(41L);
    }

    @Test
    @DisplayName("Client request payloads have the exact contracted fields")
    void requestPayloadContracts() throws Exception {
        assertThat(keys(objectMapper.valueToTree(new SendMessageRequest("hi")))).containsExactly("content");
        assertThat(keys(objectMapper.valueToTree(new MarkReadRequest(9L)))).containsExactly("upToSequenceNumber");
        assertThat(keys(objectMapper.valueToTree(new MarkDeliveredRequest(9L)))).containsExactly("upToSequenceNumber");
        assertThat(keys(objectMapper.valueToTree(new AddMemberRequest(UUID.randomUUID())))).containsExactly("userId");
        assertThat(keys(objectMapper.valueToTree(new CreateGroupRequest("t", List.of()))))
                .containsExactly("memberUserIds", "title");
        assertThat(keys(objectMapper.valueToTree(
                new UnreadCountResponse(UUID.randomUUID(), 2L, 9L))))
                .containsExactly("conversationId", "lastSequenceNumber", "unreadCount");
    }
}
