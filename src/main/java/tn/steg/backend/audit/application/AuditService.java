package tn.steg.backend.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.domain.model.AuditLog;
import tn.steg.backend.audit.domain.repository.AuditLogRepository;
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
        try {
            User actor = null;
            if (actorId != null) {
                actor = new User();
                actor.setId(actorId);
            }

            AuditLog auditLog = new AuditLog(action, entityType, entityId, actor, ipAddress);
            auditLog.setOldValues(serializeToJson(oldValues));
            auditLog.setNewValues(serializeToJson(newValues));

            auditLogRepository.save(auditLog);
        } catch (Exception ex) {
            log.error("Failed to write audit log for action={} entityType={} entityId={}: {}",
                    action, entityType, entityId, ex.getMessage());
        }
    }

    private String serializeToJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("Failed to serialize audit value to JSON: {}", ex.getMessage());
            return value.toString();
        }
    }
}
