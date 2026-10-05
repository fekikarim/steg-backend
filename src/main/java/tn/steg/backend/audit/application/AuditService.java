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
import tn.steg.backend.audit.application.dto.AuditQuery;
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.domain.model.AuditSource;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
import tn.steg.backend.common.application.ApplicationTimeZone;
import tn.steg.backend.common.infrastructure.logging.TraceIdFilter;
import tn.steg.backend.iam.domain.model.User;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationTimeZone applicationTimeZone;

    @Transactional
    public void log(String action, String entityType, UUID entityId,
                    Object oldValues, Object newValues,
                    UUID actorId, String ipAddress) {
        log(action, entityType, entityId, oldValues, newValues, actorId, ipAddress, null, null,
                AuditSource.BACK_OFFICE);
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
        log(action, entityType, entityId, oldValues, newValues, actorId, ipAddress, roleCode, traceId,
                AuditSource.BACK_OFFICE);
    }

    /**
     * S9 origin channel (§8.2): FRONT_OFFICE (public intake), BACK_OFFICE
     * (staff screens, the default), MOBILE (STEG intern app), SYSTEM
     * (schedulers, dead letters) or AI (verification runs).
     */
    @Transactional
    public void log(String action, String entityType, UUID entityId,
                    Object oldValues, Object newValues,
                    UUID actorId, String ipAddress, String roleCode, String traceId,
                    AuditSource source) {
        try {
            User actor = null;
            if (actorId != null) {
                actor = new User();
                actor.setId(actorId);
            }

            AuditLog auditLog = new AuditLog(action, entityType, entityId, actor, ipAddress);
            auditLog.setOldValues(serializeToJson(redact(oldValues)));
            auditLog.setNewValues(serializeToJson(redact(newValues)));
            auditLog.setRoleCode(roleCode);
            auditLog.setTraceId(traceId != null ? traceId : MDC.get(TraceIdFilter.MDC_TRACE_KEY));
            auditLog.setSource(source != null ? source : AuditSource.BACK_OFFICE);

            auditLogRepository.save(auditLog);
        } catch (Exception ex) {
            log.error("Failed to write audit log for action={} entityType={} entityId={}: {}",
                    action, entityType, entityId, ex.getMessage());
        }
    }

    /**
     * S9 redaction (§8.2/§10): audit payloads must never carry secrets. Any
     * map key that smells like a credential (password, token, secret, hash,
     * authorization, api key, private key — case-insensitive, substring) has
     * its value replaced, recursively through maps and lists. Pure function —
     * unit-tested without Spring.
     */
    static final String REDACTED = "[REDACTED]";

    public static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            java.util.LinkedHashMap<Object, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object entryValue = entry.getValue();
                if (key instanceof String name && isSensitiveKey(name)) {
                    out.put(key, REDACTED);
                } else {
                    out.put(key, redact(entryValue));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuditService::redact).toList();
        }
        return value;
    }

    private static boolean isSensitiveKey(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password") || lower.contains("passwd") || lower.contains("pwd")
                || lower.contains("token") || lower.contains("secret") || lower.contains("hash")
                || lower.contains("authorization") || lower.contains("apikey") || lower.contains("api_key")
                || lower.contains("privatekey") || lower.contains("private_key");
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
     * S9 read-only audit query for the Back Office viewer (§8.2). Every
     * filter combines server-side in one paged query, newest first. Never
     * exposed without ADMIN auth (enforced at the REST boundary).
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(AuditQuery query, Pageable pageable) {
        String action = query.action() == null || query.action().isBlank() ? null : query.action().strip();
        String entityType = query.entityType() == null || query.entityType().isBlank()
                ? null : query.entityType().strip();
        // Absolute instants win when present; otherwise calendar-day bounds
        // resolve in the application time zone (default Africa/Tunis).
        Instant from = query.from() != null ? query.from()
                : (query.fromDate() != null ? applicationTimeZone.startOfDay(query.fromDate()) : null);
        Instant to = query.to() != null ? query.to()
                : (query.toDate() != null ? applicationTimeZone.startOfNextDay(query.toDate()) : null);
        return auditLogRepository.searchAudit(
                        query.source(), action, entityType, query.entityId(), query.actorId(),
                        from, to, pageable)
                .map(AuditLogResponse::from);
    }

    /**
     * Legacy first-wins search, kept for existing callers.
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(String action, UUID entityId, UUID actorId, Pageable pageable) {
        return search(new AuditQuery(action, null, entityId, actorId, null, null, null), pageable);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<AuditLogResponse> getById(UUID id) {
        return auditLogRepository.findById(id).map(AuditLogResponse::from);
    }
}
