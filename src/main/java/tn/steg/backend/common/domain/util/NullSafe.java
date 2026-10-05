package tn.steg.backend.common.domain.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Null-safe collection builders for the places {@code Map.of} / {@code List.of}
 * (and {@code Map.entry}) are the wrong tool: both throw
 * {@code NullPointerException} the moment a value is {@code null}.
 *
 * <p>This bug class already hit production twice before this helper existed —
 * the task-update audit ({@code updateTask}) and the bare-supervisor review
 * audit (a {@code Map.of("validatedBy", supervisor.getId(), …)} detail that
 * turned a missing supervisor row into a 500). Audit and notification detail
 * builders describe REAL-WORLD state: an unassigned task, an internship whose
 * supervisor has no IAM user (legacy employee-backed assignments), a candidate
 * without a university or phone. Missing data must be OMITTED from the
 * payload, never crash the business action.
 *
 * <p>Keys are still required (a null key is always a caller bug and stays
 * loud); only values may be null and are silently dropped.
 */
public final class NullSafe {

    private NullSafe() {
    }

    /**
     * {@code List.of} equivalent that drops null elements (an empty input
     * yields the shared empty list). Use it everywhere a recipient/entity id
     * may legitimately be absent — e.g. {@code NullSafe.listOf(event.supervisorUserId())}.
     */
    @SafeVarargs
    public static <T> List<T> listOf(T... values) {
        List<T> list = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            for (T value : values) {
                if (value != null) {
                    list.add(value);
                }
            }
        }
        return List.copyOf(list);
    }

    /**
     * {@code Map.of} equivalent for audit/notification detail payloads: takes
     * alternating key/value pairs and OMITS every pair whose value is null.
     * Keys must be non-null (a null key is a caller bug, not missing data).
     * Insertion order is preserved so audit payloads stay stable and readable.
     */
    public static Map<String, Object> mapOf(Object... keyValuePairs) {
        if (keyValuePairs == null || keyValuePairs.length == 0) {
            return Map.of();
        }
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "NullSafe.mapOf expects alternating key/value pairs, got " + keyValuePairs.length + " arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            Object key = keyValuePairs[i];
            if (key == null) {
                throw new IllegalArgumentException("NullSafe.mapOf: null key at pair " + (i / 2));
            }
            Object value = keyValuePairs[i + 1];
            if (value != null) {
                map.put(key.toString(), value);
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
