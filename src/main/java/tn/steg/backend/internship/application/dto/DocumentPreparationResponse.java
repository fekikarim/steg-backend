package tn.steg.backend.internship.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * T14/D14 — acceptance receipt: how many interns were notified and which
 * internships were covered. The UI waits only for this acceptance, never
 * for recipient delivery (realtime path owns delivery).
 */
public record DocumentPreparationResponse(
        int notified,
        List<UUID> internshipIds
) {}
