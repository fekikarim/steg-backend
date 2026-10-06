package tn.steg.backend.companion.application.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T03 classification board: the caller's categories plus the task-to-category
 * assignment map for one internship. Only ever served to the owning student —
 * no staff endpoint or DTO exposes it (BR-08).
 */
public record TaskCategoryBoardResponse(
        List<TaskCategoryResponse> categories,
        Map<UUID, UUID> assignments
) {
}
