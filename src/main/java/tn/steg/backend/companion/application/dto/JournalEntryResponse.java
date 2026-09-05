package tn.steg.backend.companion.application.dto;

import tn.steg.backend.companion.domain.model.JournalEntry;
import tn.steg.backend.companion.domain.model.JournalEntryStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record JournalEntryResponse(
        UUID id,
        UUID journalId,
        UUID authorId,
        String authorEmail,
        UUID validatedById,
        String validatedByName,
        String title,
        String description,
        JournalEntryStatus status,
        LocalDate entryDate,
        Instant submittedAt,
        Instant validatedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static JournalEntryResponse from(JournalEntry entry) {
        String authorEmail = entry.getAuthor() != null ? entry.getAuthor().getEmail() : null;
        UUID validatedById = entry.getValidatedBy() != null ? entry.getValidatedBy().getId() : null;
        String validatedByName = null;
        if (entry.getValidatedBy() != null) {
            validatedByName = entry.getValidatedBy().getFirstName() + " " + entry.getValidatedBy().getLastName();
        }

        return new JournalEntryResponse(
                entry.getId(),
                entry.getJournal() != null ? entry.getJournal().getId() : null,
                entry.getAuthor() != null ? entry.getAuthor().getId() : null,
                authorEmail,
                validatedById,
                validatedByName,
                entry.getTitle(),
                entry.getDescription(),
                entry.getStatus(),
                entry.getEntryDate(),
                entry.getSubmittedAt(),
                entry.getValidatedAt(),
                entry.getCreatedAt(),
                entry.getUpdatedAt()
        );
    }
}
