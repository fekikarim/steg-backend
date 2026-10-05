package tn.steg.backend.ai.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.ai.domain.model.ChatbotMessage;
import tn.steg.backend.ai.domain.repository.ChatbotMessageRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** JPA adapter for {@link ChatbotMessageRepository} (S10b conversation history). */
@Repository
public interface ChatbotMessageJpaRepository
        extends JpaRepository<ChatbotMessage, UUID>, ChatbotMessageRepository {

    @Override
    List<ChatbotMessage> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Override
    long countByUserId(UUID userId);

    @Override
    @Modifying
    @Query("delete from ChatbotMessage m where m.user.id = :userId and m.createdAt < :cutoff")
    void deleteByUserIdAndCreatedAtBefore(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);

    @Override
    @Modifying
    @Query("delete from ChatbotMessage m where m.user.id = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
