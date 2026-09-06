package tn.steg.backend.audit.application.dto;

import tn.steg.backend.audit.domain.model.AuditLog;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of an {@link AuditLog} entry for the Back Office audit viewer.
 * old/new values are kept as serialized JSON snapshots exactly as recorded.
 */
public record AuditLogResponse(
        UUID id,
        Instant createdAt,
        String action,
        String entityType,
        UUID entityId,
        String oldValues,
        String newValues,
        UUID actorId,
        String actorEmail,
        String ipAddress
) {
    public static AuditLogResponse from(AuditLog entry) {
        return new AuditLogResponse(
                entry.getId(),
                entry.getCreatedAt(),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getOldValues(),
                entry.getNewValues(),
                entry.getActor() != null ? entry.getActor().getId() : null,
                entry.getActor() != null ? entry.getActor().getEmail() : null,
                entry.getIpAddress()
        );
    }
}