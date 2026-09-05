package tn.steg.backend.companion.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.companion.domain.model.Deliverable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliverableRepository {
    Optional<Deliverable> findById(UUID id);
    Deliverable save(Deliverable deliverable);
    List<Deliverable> findByInternshipId(UUID internshipId);
    Page<Deliverable> findByInternshipId(UUID internshipId, Pageable pageable);
}
