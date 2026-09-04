package tn.steg.backend.audit.domain.repository;

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
}
