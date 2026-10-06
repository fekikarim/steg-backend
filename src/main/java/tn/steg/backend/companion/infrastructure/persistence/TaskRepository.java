package tn.steg.backend.companion.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID>, tn.steg.backend.companion.domain.repository.TaskRepository {
    List<Task> findByInternshipId(UUID internshipId);
    Page<Task> findByInternshipId(UUID internshipId, Pageable pageable);
    Page<Task> findByInternshipIdAndStatus(UUID internshipId, TaskStatus status, Pageable pageable);
    Page<Task> findByStatus(TaskStatus status, Pageable pageable);
    Page<Task> findByInternshipIdIn(List<UUID> internshipIds, Pageable pageable);
    Page<Task> findByInternshipIdInAndStatus(List<UUID> internshipIds, TaskStatus status, Pageable pageable);

    /**
     * T04/D8: explicit JPQL (never a derived OR — the internship predicate
     * must bind both branches). {@code t.internship.id} is safe here:
     * {@code internship_id} is NOT NULL, so no implicit-join row loss.
     */
    @Query("select t from Task t where t.internship.id = :internshipId "
            + "and (t.visibleFrom is null or t.visibleFrom <= :now)")
    @Override
    Page<Task> findVisibleByInternshipId(
            @Param("internshipId") UUID internshipId,
            @Param("now") Instant now, Pageable pageable);

    @Query("select t from Task t where t.internship.id = :internshipId and t.status = :status "
            + "and (t.visibleFrom is null or t.visibleFrom <= :now)")
    @Override
    Page<Task> findVisibleByInternshipIdAndStatus(
            @Param("internshipId") UUID internshipId,
            @Param("status") TaskStatus status,
            @Param("now") Instant now, Pageable pageable);

    @Query("select t from Task t where t.visibleFrom is not null and t.visibleFrom <= :now "
            + "order by t.visibleFrom asc")
    @Override
    List<Task> findScheduledDue(@Param("now") Instant now, Pageable pageable);

    @Override
    default Task saveTask(Task task) {
        return save(task);
    }
}
