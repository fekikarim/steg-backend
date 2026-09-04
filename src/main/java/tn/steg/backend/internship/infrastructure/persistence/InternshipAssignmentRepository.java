package tn.steg.backend.internship.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.InternshipAssignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InternshipAssignmentRepository extends JpaRepository<InternshipAssignment, UUID> {
    List<InternshipAssignment> findByInternshipId(UUID internshipId);
    Optional<InternshipAssignment> findByInternshipIdAndStatus(UUID internshipId, AssignmentStatus status);
}
