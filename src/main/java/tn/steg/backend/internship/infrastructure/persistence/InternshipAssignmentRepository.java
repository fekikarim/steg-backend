package tn.steg.backend.internship.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.InternshipAssignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InternshipAssignmentRepository extends JpaRepository<InternshipAssignment, UUID>, tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository {
    List<InternshipAssignment> findByInternshipId(UUID internshipId);
    Optional<InternshipAssignment> findByInternshipIdAndStatus(UUID internshipId, AssignmentStatus status);

    /**
     * A14 N+1 fix backing the domain port method: history rows with
     * department/supervisor/assigner fetched eagerly (all rendered per row).
     */
    @Query("SELECT DISTINCT a FROM InternshipAssignment a " +
           "LEFT JOIN FETCH a.destination LEFT JOIN FETCH a.supervisor LEFT JOIN FETCH a.assignedBy " +
           "WHERE a.internship.id = :internshipId")
    List<InternshipAssignment> findByInternshipIdWithDetails(@Param("internshipId") UUID internshipId);
}
