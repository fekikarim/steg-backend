package tn.steg.backend.companion.application.dto;

import java.util.UUID;

/**
 * T03 one AI classification proposal (ST-TASK-04): advisory only, nothing is
 * persisted until the student accepts it. Either {@code categoryId} (one of
 * the student's existing categories) or {@code newCategoryName} (shown as
 * "new", created only on accept) is set — never both.
 */
public record CategoryProposalResponse(
        UUID taskId,
        UUID categoryId,
        String newCategoryName,
        double confidence,
        boolean isNewCategory
) {
}
