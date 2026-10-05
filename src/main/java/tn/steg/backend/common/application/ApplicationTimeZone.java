package tn.steg.backend.common.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Application time zone (default {@code Africa/Tunis}, configurable via
 * {@code steg.timezone} / {@code APP_TIMEZONE}).
 *
 * <p>Persistence stores absolute instants (UTC); every CALENDAR-DAY boundary
 * used by a date-range filter (candidates, applications, audit, notifications)
 * is resolved through this zone, never through {@code ZoneOffset.UTC} or the
 * JVM default zone. A row created at 00:10 Tunis time therefore belongs to
 * "today" for a Tunis user even though it is still "yesterday" in UTC.
 */
@Slf4j
@Component
public class ApplicationTimeZone {

    private final ZoneId zoneId;

    public ApplicationTimeZone(@Value("${steg.timezone:Africa/Tunis}") String zoneName) {
        String effective = zoneName == null || zoneName.isBlank() ? "Africa/Tunis" : zoneName.strip();
        this.zoneId = ZoneId.of(effective);
        log.info("Application time zone: {}", this.zoneId);
    }

    /** Configured application zone (e.g. Africa/Tunis). */
    public ZoneId zoneId() {
        return zoneId;
    }

    /** "Today" in the application zone (e.g. for server-stamped dates). */
    public LocalDate today() {
        return LocalDate.now(zoneId);
    }

    /** Inclusive lower instant bound of a calendar day in the application zone. */
    public Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(zoneId).toInstant();
    }

    /** Exclusive upper instant bound of a calendar day in the application zone. */
    public Instant startOfNextDay(LocalDate day) {
        return day.plusDays(1).atStartOfDay(zoneId).toInstant();
    }
}
