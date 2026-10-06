package tn.steg.backend.companion.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for Task persistence.
 * save() is intentionally absent: it is inherited from JpaRepository in the
 * infrastructure adapter and should be called via the infra repo when needed
 * outside of CompanionService (e.g. tests use saveAndFlush).
 */
public interface TaskRepository {
    Optional<Task> findById(UUID id);
    List<Task> findByInternshipId(UUID internshipId);
    Page<Task> findByInternshipId(UUID internshipId, Pageable pageable);
    Page<Task> findByInternshipIdAndStatus(UUID internshipId, TaskStatus status, Pageable pageable);
    Page<Task> findAll(Pageable pageable);
    Page<Task> findByStatus(TaskStatus status, Pageable pageable);
    Page<Task> findByInternshipIdIn(List<UUID> internshipIds, Pageable pageable);
    Page<Task> findByInternshipIdInAndStatus(List<UUID> internshipIds, TaskStatus status, Pageable pageable);
    Task saveTask(Task task);
    void delete(Task task);

    /**
     * T04/D8 student-facing reads: only tasks visible at {@code now}
     * (immediate or already appeared). Staff reads use the unfiltered
     * finders above.
     */
    Page<Task> findVisibleByInternshipId(UUID internshipId, Instant now, Pageable pageable);
    Page<Task> findVisibleByInternshipIdAndStatus(
            UUID internshipId, TaskStatus status, Instant now, Pageable pageable);

    /** T04/D8 scheduler sweep: scheduled tasks whose moment has passed. */
    List<Task> findScheduledDue(Instant now, Pageable pageable);
}
