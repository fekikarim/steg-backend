package tn.steg.backend.ai.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.ai.domain.model.ChatbotMessage;

import java.time.Instant;
import java.util.List;

@Schema(description = "Caller-owned chatbot turns, oldest first (bounded server-side)")
public record ChatbotHistoryResponse(
        List<Turn> turns
) {
    public record Turn(String role, String content, Instant createdAt) {
        static Turn from(ChatbotMessage message) {
            return new Turn(message.getRole().name(), message.getContent(), message.getCreatedAt());
        }
    }

    public static ChatbotHistoryResponse of(List<ChatbotMessage> messages) {
        return new ChatbotHistoryResponse(messages.stream().map(Turn::from).toList());
    }
}
