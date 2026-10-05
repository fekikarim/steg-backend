package tn.steg.backend.audit.application.dto;

import tn.steg.backend.audit.domain.model.AuditSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * S9 audit viewer query (§8.2): every filter combines server-side in one
 * paged query (unlike the old first-wins search).
 *
 * <p>Two date-filter shapes are accepted: absolute {@code from}/{@code to}
 * instants (exact, time-zone independent) and calendar-day
 * {@code fromDate}/{@code toDate} bounds resolved in the application time
 * zone (default Africa/Tunis). When both shapes are present the absolute
 * instants win.
 */
public record AuditQuery(
        String action,
        String entityType,
        UUID entityId,
        UUID actorId,
        AuditSource source,
        Instant from,
        Instant to,
        LocalDate fromDate,
        LocalDate toDate
) {
    /** Backward-compatible shape without calendar-day bounds. */
    public AuditQuery(String action, String entityType, UUID entityId, UUID actorId,
                      AuditSource source, Instant from, Instant to) {
        this(action, entityType, entityId, actorId, source, from, to, null, null);
    }
}
