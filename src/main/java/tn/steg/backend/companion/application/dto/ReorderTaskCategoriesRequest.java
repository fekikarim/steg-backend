package tn.steg.backend.companion.application.dto;

import java.util.List;
import java.util.UUID;

/** T03 reorder payload: the full ordered category ids of the caller. */
public record ReorderTaskCategoriesRequest(
        List<UUID> orderedIds
) {
}
