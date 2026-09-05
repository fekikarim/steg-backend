package tn.steg.backend.companion.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.companion.domain.model.JournalEntry;
import tn.steg.backend.companion.domain.model.JournalEntryStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository {
    Optional<JournalEntry> findById(UUID id);
    JournalEntry save(JournalEntry journalEntry);
    List<JournalEntry> findByJournalId(UUID journalId);
    Page<JournalEntry> findByJournalId(UUID journalId, Pageable pageable);
    Page<JournalEntry> findByJournalIdAndStatus(UUID journalId, JournalEntryStatus status, Pageable pageable);
    Page<JournalEntry> findByJournalIdAndEntryDateBetween(UUID journalId, LocalDate startDate, LocalDate endDate, Pageable pageable);
    Page<JournalEntry> findByJournalIdAndStatusAndEntryDateBetween(UUID journalId, JournalEntryStatus status, LocalDate startDate, LocalDate endDate, Pageable pageable);
}
