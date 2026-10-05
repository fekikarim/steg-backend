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

    @Query(value = "SELECT a FROM AuditLog a "
            + "WHERE (:source IS NULL OR a.source = :source) "
            + "AND (:action IS NULL OR a.action = :action) "
            + "AND (:entityType IS NULL OR a.entityType = :entityType) "
            + "AND (:entityId IS NULL OR a.entityId = :entityId) "
            + "AND (:actorId IS NULL OR a.actor.id = :actorId) "
            + "AND (CAST(:from AS timestamp) IS NULL OR a.createdAt >= :from) "
            + "AND (CAST(:to AS timestamp) IS NULL OR a.createdAt <= :to)",
            countQuery = "SELECT COUNT(a) FROM AuditLog a "
                    + "WHERE (:source IS NULL OR a.source = :source) "
                    + "AND (:action IS NULL OR a.action = :action) "
                    + "AND (:entityType IS NULL OR a.entityType = :entityType) "
                    + "AND (:entityId IS NULL OR a.entityId = :entityId) "
                    + "AND (:actorId IS NULL OR a.actor.id = :actorId) "
                    + "AND (CAST(:from AS timestamp) IS NULL OR a.createdAt >= :from) "
                    + "AND (CAST(:to AS timestamp) IS NULL OR a.createdAt <= :to)")
    org.springframework.data.domain.Page<AuditLog> searchAudit(
            @Param("source") tn.steg.backend.audit.domain.model.AuditSource source,
            @Param("action") String action,
            @Param("entityType") String entityType,
            @Param("entityId") UUID entityId,
            @Param("actorId") UUID actorId,
            @Param("from") java.time.Instant from,
            @Param("to") java.time.Instant to,
            org.springframework.data.domain.Pageable pageable);
}