package tn.steg.backend.audit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.audit.domain.model.AuditLog;

import java.util.UUID;

/**
 * Spring Data JPA adapter for the domain {@link tn.steg.backend.audit.domain.repository.AuditLogRepository} port.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, tn.steg.backend.audit.domain.repository.AuditLogRepository {

    @Query("select a from AuditLog a where a.actor.id = :actorId")
    org.springframework.data.domain.Page<AuditLog> findByActorId(
            @Param("actorId") UUID actorId, org.springframework.data.domain.Pageable pageable);
}