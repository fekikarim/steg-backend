package tn.steg.backend.internship.domain.repository;

import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.InternshipAssignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternshipAssignmentRepository {
    List<InternshipAssignment> findByInternshipId(UUID internshipId);
    /**
     * A14 N+1 fix: assignment history renders department/supervisor/assigner
     * names per row. Fetch all three in the single history query.
     */
    List<InternshipAssignment> findByInternshipIdWithDetails(UUID internshipId);
    Optional<InternshipAssignment> findByInternshipIdAndStatus(UUID internshipId, AssignmentStatus status);
    /**
     * ACTIVE assignments held by one supervisor user (staff-assistant scoping,
     * supervisor dashboards). Status is a parameter so callers stay explicit.
     */
    List<InternshipAssignment> findBySupervisorUserIdAndStatus(UUID supervisorUserId, AssignmentStatus status);
    /** S5 candidate queue: ACTIVE assignments of a whole page of internships in one query. */
    List<InternshipAssignment> findByInternshipIdInAndStatus(java.util.Collection<UUID> internshipIds, AssignmentStatus status);
    InternshipAssignment save(InternshipAssignment assignment);
    InternshipAssignment saveAndFlush(InternshipAssignment assignment);
}
