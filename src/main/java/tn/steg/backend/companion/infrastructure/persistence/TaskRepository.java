package tn.steg.backend.companion.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;

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

    @Override
    default Task saveTask(Task task) {
        return save(task);
    }
}
