package tn.steg.backend.companion.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.companion.domain.model.InternshipJournal;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InternshipJournalRepository extends JpaRepository<InternshipJournal, UUID> {
    Optional<InternshipJournal> findByInternshipId(UUID internshipId);
}
