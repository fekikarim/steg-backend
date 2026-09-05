package tn.steg.backend.companion.application.dto;

import java.time.LocalDate;

public record JournalEntryRequest(
        String title,
        String description,
        LocalDate entryDate
) {}
