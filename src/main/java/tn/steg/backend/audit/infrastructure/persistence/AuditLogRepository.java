package tn.steg.backend.audit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.audit.domain.model.AuditLog;

import java.util.UUID;

/**
 * Spring Data JPA adapter for the domain {@link tn.steg.backend.audit.domain.repository.AuditLogRepository} port.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, tn.steg.backend.audit.domain.repository.AuditLogRepository {
}
