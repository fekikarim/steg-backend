package tn.steg.backend.companion.application.dto;

import java.util.List;

/** T03 AI suggestion answer: proposals only, nothing persisted (ST-TASK-04). */
public record SuggestTaskCategoriesResponse(
        List<CategoryProposalResponse> proposals,
        int unclassifiedTaskCount,
        boolean capped
) {
}
