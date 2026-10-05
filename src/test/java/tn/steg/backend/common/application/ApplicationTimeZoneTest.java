package tn.steg.backend.common.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Application time zone (pre-fix (a)): every calendar-day date-range filter
 * resolves its day boundaries in the configured zone, default Africa/Tunis.
 */
@DisplayName("Application time zone")
class ApplicationTimeZoneTest {

    @Test
    @DisplayName("defaults to Africa/Tunis when nothing is configured")
    void defaultsToTunis() {
        assertThat(new ApplicationTimeZone(null).zoneId()).isEqualTo(ZoneId.of("Africa/Tunis"));
        assertThat(new ApplicationTimeZone("  ").zoneId()).isEqualTo(ZoneId.of("Africa/Tunis"));
    }

    @Test
    @DisplayName("honours an explicit zone name")
    void honoursExplicitZone() {
        assertThat(new ApplicationTimeZone("UTC").zoneId()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("rejects an unknown zone instead of silently falling back")
    void rejectsUnknownZone() {
        assertThatThrownBy(() -> new ApplicationTimeZone("Mars/Olympus"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("day bounds of 2026-10-04 Tunis are 23:00Z..23:00Z next day (UTC+1)")
    void tunisDayBounds() {
        ApplicationTimeZone tz = new ApplicationTimeZone("Africa/Tunis");
        LocalDate day = LocalDate.of(2026, 10, 4);

        Instant from = tz.startOfDay(day);
        Instant toExclusive = tz.startOfNextDay(day);

        assertThat(from).isEqualTo(Instant.parse("2026-10-03T23:00:00Z"));
        assertThat(toExclusive).isEqualTo(Instant.parse("2026-10-04T23:00:00Z"));

        // 00:10 Tunis belongs to the Tunis day, 23:10 UTC the day before does not belong to UTC-today.
        Instant tenPastMidnightTunis = day.atTime(0, 10).atZone(tz.zoneId()).toInstant();
        assertThat(tenPastMidnightTunis).isEqualTo(Instant.parse("2026-10-03T23:10:00Z"));
        assertThat(tenPastMidnightTunis).isAfterOrEqualTo(from);
        assertThat(tenPastMidnightTunis).isBefore(toExclusive);
    }
}
