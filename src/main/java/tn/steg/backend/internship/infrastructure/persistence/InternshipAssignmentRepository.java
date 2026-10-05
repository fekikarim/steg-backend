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

    /**
     * ACTIVE (or any) assignments held by one supervisor user, matching BOTH
     * the user-backed link and the legacy employee link.
     *
     * <p>The legacy link is an explicit LEFT JOIN on purpose: an implicit join
     * path ({@code a.supervisor.user.id}) compiles to an INNER JOIN, so every
     * user-backed assignment (legacy {@code supervisor_id} NULL) was silently
     * filtered out of supervision scope, delete guards and notification
     * routing.
     */
    @Query("SELECT a FROM InternshipAssignment a LEFT JOIN a.supervisor legacySupervisor " +
           "WHERE (a.supervisorUser.id = :supervisorUserId OR legacySupervisor.user.id = :supervisorUserId) " +
           "AND a.status = :status")
    List<InternshipAssignment> findBySupervisorUserIdAndStatus(
            @Param("supervisorUserId") UUID supervisorUserId,
            @Param("status") AssignmentStatus status);
}
