package tn.steg.backend.companion.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.companion.domain.model.TaskDraft;
import tn.steg.backend.companion.domain.repository.TaskDraftRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA adapter for the {@link TaskDraftRepository} domain port (S10a).
 */
@Repository
public interface TaskDraftJpaRepository
        extends JpaRepository<TaskDraft, UUID>, TaskDraftRepository {

    @Override
    List<TaskDraft> findByCreatedByIdOrderByCreatedAtDesc(UUID createdById);
}
