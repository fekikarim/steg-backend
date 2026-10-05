package tn.steg.backend.internship.application.dto;

import tn.steg.backend.internship.domain.model.InternshipType;

import java.util.List;
import java.util.UUID;

/**
 * S7 validation queue query (AGENTS.md §5.11 step 1). All filters run
 * server-side in one paged query; Admin-only (a Supervisor gets 403 —
 * validation + receipt are Admin capabilities, §3.3).
 */
public record ValidationQueueQuery(
        int page,
        int size,
        String sort,
        String q,
        List<String> statuses,
        UUID supervisorUserId,
        UUID universityId,
        InternshipType type
) {
}
