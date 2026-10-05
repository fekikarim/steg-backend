package tn.steg.backend.ai.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.iam.domain.model.User;

/**
 * One turn of a back-office chatbot conversation (AGENTS.md §7.5).
 *
 * <p>History is per user and server-side: the owner is the {@code User} row,
 * so a user can never read another user's turns. Bounded by the service
 * (keeps the latest {@code MAX_TURNS}); message bodies are user/model text —
 * audit entries reference counts only, never content.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_chat_messages")
public class ChatbotMessage extends BaseEntity {

    public enum Role {
        USER,
        ASSISTANT
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    public ChatbotMessage(User user, Role role, String content) {
        this.user = user;
        this.role = role;
        this.content = content;
    }
}
