package tn.steg.backend.ai.domain.repository;

import tn.steg.backend.ai.domain.model.ChatbotMessage;

import java.util.List;
import java.util.UUID;

/** Domain port for per-user chatbot conversation history (bounded, server-side). */
public interface ChatbotMessageRepository {

    ChatbotMessage save(ChatbotMessage message);

    /** Latest turns first (caller slices to the bound). */
    List<ChatbotMessage> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserId(UUID userId);

    /** Deletes all turns strictly older than the given message (keeps the bound). */
    void deleteByUserIdAndCreatedAtBefore(UUID userId, java.time.Instant cutoff);

    void deleteById(UUID id);

    void deleteByUserId(UUID userId);
}
