package tn.steg.backend.companion.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.companion.domain.model.JournalEntry;
import tn.steg.backend.companion.domain.model.JournalEntryStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID>, tn.steg.backend.companion.domain.repository.JournalEntryRepository {
    List<JournalEntry> findByJournalId(UUID journalId);
    Page<JournalEntry> findByJournalId(UUID journalId, Pageable pageable);
    Page<JournalEntry> findByJournalIdAndStatus(UUID journalId, JournalEntryStatus status, Pageable pageable);
    Page<JournalEntry> findByJournalIdAndEntryDateBetween(UUID journalId, LocalDate startDate, LocalDate endDate, Pageable pageable);
    Page<JournalEntry> findByJournalIdAndStatusAndEntryDateBetween(UUID journalId, JournalEntryStatus status, LocalDate startDate, LocalDate endDate, Pageable pageable);
}
