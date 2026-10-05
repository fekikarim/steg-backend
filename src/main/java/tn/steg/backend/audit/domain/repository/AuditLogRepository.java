package tn.steg.backend.audit.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.audit.domain.model.AuditLog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port interface for AuditLog persistence operations.
 */
public interface AuditLogRepository {
    AuditLog save(AuditLog auditLog);
    Optional<AuditLog> findById(UUID id);
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(String entityType, UUID entityId);

    Page<AuditLog> findAll(Pageable pageable);
    Page<AuditLog> findByAction(String action, Pageable pageable);
    Page<AuditLog> findByEntityId(UUID entityId, Pageable pageable);
    Page<AuditLog> findByActorId(UUID actorId, Pageable pageable);

    /**
     * S9 combinable viewer search (§8.2): all filters AND together in one
     * paged query, newest first.
     */
    Page<AuditLog> searchAudit(tn.steg.backend.audit.domain.model.AuditSource source,
            String action, String entityType, UUID entityId, UUID actorId,
            java.time.Instant from, java.time.Instant to, Pageable pageable);
}