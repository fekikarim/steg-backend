package tn.steg.backend.messaging.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.messaging.domain.model.Conversation;
import tn.steg.backend.messaging.domain.model.ConversationType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaConversationRepository
        extends JpaRepository<Conversation, UUID>,
        tn.steg.backend.messaging.domain.repository.ConversationRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> findByIdForUpdate(@Param("id") UUID id);

    @Query("select c from Conversation c where c.internship.id = :internshipId")
    List<Conversation> findByInternshipId(@Param("internshipId") UUID internshipId);

    @Query("select c from Conversation c where c.internship.id = :internshipId and c.type = 'PRIVATE'")
    Optional<Conversation> findPrivateByInternshipId(@Param("internshipId") UUID internshipId);

    List<Conversation> findByType(ConversationType type);
}
