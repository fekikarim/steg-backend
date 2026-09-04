package tn.steg.backend.internship.domain.repository;

import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.InternshipAssignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternshipAssignmentRepository {
    List<InternshipAssignment> findByInternshipId(UUID internshipId);
    Optional<InternshipAssignment> findByInternshipIdAndStatus(UUID internshipId, AssignmentStatus status);
    InternshipAssignment save(InternshipAssignment assignment);
    InternshipAssignment saveAndFlush(InternshipAssignment assignment);
}
