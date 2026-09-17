package tn.steg.backend.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.application.dto.AuditLogResponse;
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.common.infrastructure.logging.TraceIdFilter;
import tn.steg.backend.iam.domain.model.User;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void log(String action, String entityType, UUID entityId,
                    Object oldValues, Object newValues,
                    UUID actorId, String ipAddress) {
        log(action, entityType, entityId, oldValues, newValues, actorId, ipAddress, null, null);
    }

    /**
     * E1.5 full audit record: actor, role snapshot, resource, action, timestamp
     * (via {@code createdAt}), outcome (in newValues), correlation id.
     * A null traceId falls back to the request MDC trace (see {@link TraceIdFilter});
     * async/event paths without MDC record null. Never throws.
     */
    @Transactional
    public void log(String action, String entityType, UUID entityId,
                    Object oldValues, Object newValues,
                    UUID actorId, String ipAddress, String roleCode, String traceId) {
        try {
            User actor = null;
            if (actorId != null) {
                actor = new User();
                actor.setId(actorId);
            }

            AuditLog auditLog = new AuditLog(action, entityType, entityId, actor, ipAddress);
            auditLog.setOldValues(serializeToJson(oldValues));
            auditLog.setNewValues(serializeToJson(newValues));
            auditLog.setRoleCode(roleCode);
            auditLog.setTraceId(traceId != null ? traceId : MDC.get(TraceIdFilter.MDC_TRACE_KEY));

            auditLogRepository.save(auditLog);
        } catch (Exception ex) {
            log.error("Failed to write audit log for action={} entityType={} entityId={}: {}",
                    action, entityType, entityId, ex.getMessage());
        }
    }

    /**
     * Role snapshot for audit rows: first granted role without the {@code ROLE_} prefix.
     * Callers holding the {@code UserPrincipal} should pass this as {@code roleCode}.
     */
    public static String primaryRole(java.util.List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        String first = roles.get(0);
        return first.startsWith("ROLE_") ? first.substring("ROLE_".length()) : first;
    }

    private String serializeToJson(Object value) {        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("Failed to serialize audit value to JSON: {}", ex.getMessage());
            return value.toString();
        }
    }

    /**
     * Read-only audit query for the Back Office viewer. Mutually exclusive
     * filters: action, entityId, or actorId (first present wins); no filter
     * returns the complete log, newest first. Never exposed without ADMIN auth
     * (enforced at the REST boundary).
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(String action, UUID entityId, UUID actorId, Pageable pageable) {
        Page<AuditLog> page;
        if (action != null && !action.isBlank()) {
            page = auditLogRepository.findByAction(action, pageable);
        } else if (entityId != null) {
            page = auditLogRepository.findByEntityId(entityId, pageable);
        } else if (actorId != null) {
            page = auditLogRepository.findByActorId(actorId, pageable);
        } else {
            page = auditLogRepository.findAll(pageable);
        }
        return page.map(AuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<AuditLogResponse> getById(UUID id) {
        return auditLogRepository.findById(id).map(AuditLogResponse::from);
    }
}
