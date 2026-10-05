package tn.steg.backend.common.domain.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 3 — the null-safe builders that replace {@code Map.of}/{@code List.of}
 * in audit, notification and event detail builders. Both JDK builders throw
 * NPE on any null value; this bug class already hit production twice (the
 * task-update audit and the bare-supervisor review audit — a missing
 * supervisor row became a 500). Named paths: no supervisor, no assignee,
 * no university, no phone.
 */
@DisplayName("NullSafe — audit/notification detail builders tolerate missing real-world data")
class NullSafeTest {

    @Test
    @DisplayName("listOf drops a null supervisor id (no supervisor) but keeps the other recipients")
    void listOfDropsNullSupervisor() {
        UUID internId = UUID.randomUUID();
        assertThat(NullSafe.listOf(internId, (UUID) null))
                .containsExactly(internId);
    }

    @Test
    @DisplayName("listOf drops a null assignee id (no assignee) — result is empty, never NPE")
    void listOfDropsNullAssignee() {
        assertThat(NullSafe.listOf((UUID) null)).isEmpty();
        assertThat(NullSafe.listOf()).isEmpty();
    }

    @Test
    @DisplayName("mapOf omits null university and null phone but keeps the other detail entries")
    void mapOfOmitsNullUniversityAndPhone() {
        Map<String, Object> detail = NullSafe.mapOf(
                "candidate", "Karim Feki",
                "university", null,
                "phone", null,
                "status", "VALIDATED");
        assertThat(detail)
                .containsEntry("candidate", "Karim Feki")
                .containsEntry("status", "VALIDATED")
                .doesNotContainKey("university")
                .doesNotContainKey("phone");
    }

    @Test
    @DisplayName("mapOf preserves insertion order and stays unmodifiable")
    void mapOfPreservesOrderAndIsUnmodifiable() {
        Map<String, Object> detail = NullSafe.mapOf("b", 2, "a", 1, "c", null);
        assertThat(detail.keySet()).containsExactly("b", "a");
        assertThatThrownBy(() -> detail.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("mapOf keeps failing loudly on caller bugs: null keys and odd argument counts")
    void mapOfRejectsCallerBugs() {
        assertThatThrownBy(() -> NullSafe.mapOf(null, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null key");
        assertThatThrownBy(() -> NullSafe.mapOf("only-a-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alternating key/value");
        assertThat(NullSafe.mapOf()).isEmpty();
    }
}
