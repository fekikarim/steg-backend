package tn.steg.backend.companion.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository {
    Optional<Task> findById(UUID id);
    Task save(Task task);
    List<Task> findByInternshipId(UUID internshipId);
    Page<Task> findByInternshipId(UUID internshipId, Pageable pageable);
    Page<Task> findByInternshipIdAndStatus(UUID internshipId, TaskStatus status, Pageable pageable);
}
