package tn.steg.backend.companion.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.companion.domain.model.Logbook;
import tn.steg.backend.companion.domain.model.LogbookStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LogbookRepository extends JpaRepository<Logbook, UUID>, tn.steg.backend.companion.domain.repository.LogbookRepository {

    Optional<Logbook> findByInternshipId(UUID internshipId);

    Optional<Logbook> findByInternshipIdAndStatus(UUID internshipId, LogbookStatus status);

    List<Logbook> findBySubmittedBy(UUID userId);

    List<Logbook> findByStatus(LogbookStatus status);
}