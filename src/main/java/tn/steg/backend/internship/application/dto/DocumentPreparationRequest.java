package tn.steg.backend.internship.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * T14/D14 — supervisor asks his own students to prepare validation
 * documents. Internship ids (the supervisor's scope unit), validated
 * against {@code SupervisionScopeService} — never candidate ids, never
 * widenable by the caller.
 */
public record DocumentPreparationRequest(
        List<UUID> internshipIds
) {}
