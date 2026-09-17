package tn.steg.backend.companion.domain.repository;

import tn.steg.backend.companion.domain.model.Logbook;
import tn.steg.backend.companion.domain.model.LogbookStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port interface for Logbook persistence operations.
 */
public interface LogbookRepository {
    Logbook save(Logbook logbook);
    Optional<Logbook> findById(UUID id);
    Optional<Logbook> findByInternshipId(UUID internshipId);
    Optional<Logbook> findByInternshipIdAndStatus(UUID internshipId, LogbookStatus status);
    List<Logbook> findBySubmittedBy(UUID userId);
    List<Logbook> findByStatus(LogbookStatus status);
}