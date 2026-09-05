package tn.steg.backend.companion.domain.repository;

import tn.steg.backend.companion.domain.model.InternshipJournal;

import java.util.Optional;
import java.util.UUID;

public interface InternshipJournalRepository {
    Optional<InternshipJournal> findById(UUID id);
    Optional<InternshipJournal> findByInternshipId(UUID internshipId);
    InternshipJournal save(InternshipJournal journal);
}
