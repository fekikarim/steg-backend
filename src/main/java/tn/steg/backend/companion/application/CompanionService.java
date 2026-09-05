package tn.steg.backend.companion.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.application.dto.*;
import tn.steg.backend.companion.domain.model.*;
import tn.steg.backend.companion.domain.repository.DeliverableRepository;
import tn.steg.backend.companion.domain.repository.DeliverableVersionRepository;
import tn.steg.backend.companion.domain.repository.InternshipJournalRepository;
import tn.steg.backend.companion.domain.repository.JournalEntryRepository;
import tn.steg.backend.companion.domain.repository.TaskRepository;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.domain.repository.FileAssetRepository;
import tn.steg.backend.document.domain.service.DocumentValidationService;
import tn.steg.backend.document.domain.service.FileStorageService;
import tn.steg.backend.document.domain.service.MalwareScanner;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionService {

    private final TaskRepository taskRepository;
    private final InternshipJournalRepository journalRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final DeliverableRepository deliverableRepository;
    private final DeliverableVersionRepository deliverableVersionRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final FileStorageService fileStorageService;
    private final FileAssetRepository fileAssetRepository;
    private final DocumentValidationService documentValidationService;
    private final MalwareScanner malwareScanner;

    // -------------------------------------------------------------------------
    // Tasks
    // -------------------------------------------------------------------------

    @Transactional
    public TaskResponse createTask(UUID internshipId, TaskRequest request, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);
        User creator = findUserOrThrow(actor.getId());

        User assignedTo = null;
        if (request.assignedToId() != null) {
            assignedTo = findUserOrThrow(request.assignedToId());
        }

        Task task = new Task(internship, creator, request.title(), request.description());
        task.setAssignedTo(assignedTo);
        task.setDueDate(request.dueDate());
        if (request.status() != null) {
            task.setStatus(request.status());
            if (request.status() == TaskStatus.COMPLETED) {
                task.setCompletedAt(Instant.now());
            }
        }

        task = taskRepository.save(task);
        log.info("Task created: id={}, internship={}, creator={}", task.getId(), internshipId, creator.getId());
        return TaskResponse.from(task);
    }

    @Transactional(readOnly = true)
    public Page<TaskResponse> listTasks(UUID internshipId, TaskStatus status, Pageable pageable) {
        findInternshipOrThrow(internshipId);
        Page<Task> page = (status != null)
                ? taskRepository.findByInternshipIdAndStatus(internshipId, status, pageable)
                : taskRepository.findByInternshipId(internshipId, pageable);
        return page.map(TaskResponse::from);
    }

    @Transactional
    public TaskResponse updateTask(UUID taskId, TaskRequest request, UserPrincipal actor) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));

        if (request.title() != null && !request.title().isBlank()) {
            task.setTitle(request.title());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
        }
        if (request.assignedToId() != null) {
            task.setAssignedTo(findUserOrThrow(request.assignedToId()));
        }
        if (request.dueDate() != null) {
            task.setDueDate(request.dueDate());
        }
        if (request.status() != null) {
            task.setStatus(request.status());
            if (request.status() == TaskStatus.COMPLETED && task.getCompletedAt() == null) {
                task.setCompletedAt(Instant.now());
            } else if (request.status() != TaskStatus.COMPLETED) {
                task.setCompletedAt(null);
            }
        }

        task = taskRepository.save(task);
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse updateTaskStatus(UUID taskId, TaskStatus status, UserPrincipal actor) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));

        task.setStatus(status);
        if (status == TaskStatus.COMPLETED) {
            task.setCompletedAt(Instant.now());
        } else {
            task.setCompletedAt(null);
        }

        task = taskRepository.save(task);
        return TaskResponse.from(task);
    }

    // -------------------------------------------------------------------------
    // Journal & Journal Entries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<JournalEntryResponse> listJournalEntries(UUID internshipId, JournalEntryStatus status,
                                                        LocalDate startDate, LocalDate endDate, Pageable pageable) {
        InternshipJournal journal = findOrCreateJournal(internshipId);
        Page<JournalEntry> page;

        if (status != null && startDate != null && endDate != null) {
            page = journalEntryRepository.findByJournalIdAndStatusAndEntryDateBetween(journal.getId(), status, startDate, endDate, pageable);
        } else if (status != null) {
            page = journalEntryRepository.findByJournalIdAndStatus(journal.getId(), status, pageable);
        } else if (startDate != null && endDate != null) {
            page = journalEntryRepository.findByJournalIdAndEntryDateBetween(journal.getId(), startDate, endDate, pageable);
        } else {
            page = journalEntryRepository.findByJournalId(journal.getId(), pageable);
        }

        return page.map(JournalEntryResponse::from);
    }

    @Transactional
    public JournalEntryResponse createJournalEntry(UUID internshipId, JournalEntryRequest request, UserPrincipal actor) {
        InternshipJournal journal = findOrCreateJournal(internshipId);
        User author = findUserOrThrow(actor.getId());

        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : LocalDate.now();
        JournalEntry entry = new JournalEntry(journal, author, request.title(), request.description(), entryDate);
        entry.setStatus(JournalEntryStatus.DRAFT);

        entry = journalEntryRepository.save(entry);
        log.info("Journal entry created: id={}, journal={}, author={}", entry.getId(), journal.getId(), author.getId());
        return JournalEntryResponse.from(entry);
    }

    @Transactional
    public JournalEntryResponse submitJournalEntry(UUID entryId, UserPrincipal actor) {
        JournalEntry entry = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found: " + entryId));

        if (entry.getStatus() != JournalEntryStatus.DRAFT && entry.getStatus() != JournalEntryStatus.REJECTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only DRAFT or REJECTED journal entries can be submitted. Current status: " + entry.getStatus());
        }

        entry.setStatus(JournalEntryStatus.SUBMITTED);
        entry.setSubmittedAt(Instant.now());
        entry = journalEntryRepository.save(entry);
        log.info("Journal entry submitted: id={}", entry.getId());
        return JournalEntryResponse.from(entry);
    }

    @Transactional
    public JournalEntryResponse validateJournalEntry(UUID entryId, ValidationRequest request, UserPrincipal actor) {
        JournalEntry entry = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found: " + entryId));

        if (entry.getStatus() != JournalEntryStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only SUBMITTED journal entries can be validated. Current status: " + entry.getStatus());
        }

        // Supervisor validation check: actor must be active supervisor or HR/ADMIN
        Employee supervisor = findSupervisorForInternship(entry.getJournal().getInternship().getId(), actor);

        entry.setStatus(JournalEntryStatus.VALIDATED);
        entry.setValidatedBy(supervisor);
        entry.setValidatedAt(Instant.now());
        entry = journalEntryRepository.save(entry);
        log.info("Journal entry validated: id={}, validatedBy={}", entry.getId(), supervisor != null ? supervisor.getId() : null);
        return JournalEntryResponse.from(entry);
    }

    @Transactional
    public JournalEntryResponse rejectJournalEntry(UUID entryId, ValidationRequest request, UserPrincipal actor) {
        JournalEntry entry = journalEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Journal entry not found: " + entryId));

        if (entry.getStatus() != JournalEntryStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only SUBMITTED journal entries can be rejected. Current status: " + entry.getStatus());
        }

        Employee supervisor = findSupervisorForInternship(entry.getJournal().getInternship().getId(), actor);

        entry.setStatus(JournalEntryStatus.REJECTED);
        entry.setValidatedBy(supervisor);
        entry.setValidatedAt(Instant.now());
        entry = journalEntryRepository.save(entry);
        log.info("Journal entry rejected: id={}, rejectedBy={}", entry.getId(), supervisor != null ? supervisor.getId() : null);
        return JournalEntryResponse.from(entry);
    }

    // -------------------------------------------------------------------------
    // Deliverables
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<DeliverableResponse> listDeliverables(UUID internshipId, Pageable pageable) {
        findInternshipOrThrow(internshipId);
        Page<Deliverable> deliverables = deliverableRepository.findByInternshipId(internshipId, pageable);
        return deliverables.map(this::toDeliverableResponse);
    }

    @Transactional(readOnly = true)
    public DeliverableResponse getDeliverable(UUID deliverableId) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));
        return toDeliverableResponse(deliverable);
    }

    @Transactional
    public DeliverableResponse createDeliverable(UUID internshipId, String title, String description,
                                                 MultipartFile file, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);
        User uploader = findUserOrThrow(actor.getId());

        Deliverable deliverable = new Deliverable(internship, title, description);
        deliverable.setStatus(DeliverableStatus.DRAFT);
        deliverable.setCurrentVersion(1);
        deliverable = deliverableRepository.save(deliverable);

        FileAsset fileAsset = storeFileAsset(file, uploader, DocumentType.STEG_INTERNSHIP_REPORT);

        DeliverableVersion version = new DeliverableVersion(deliverable, fileAsset, uploader, 1, "Initial version");
        version = deliverableVersionRepository.save(version);

        log.info("Deliverable created: id={}, v1 fileAsset={}, uploader={}", deliverable.getId(), fileAsset.getId(), uploader.getId());
        return toDeliverableResponse(deliverable);
    }

    @Transactional
    public DeliverableResponse uploadNewVersion(UUID deliverableId, MultipartFile file, String changeSummary, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        if (deliverable.getStatus() == DeliverableStatus.VALIDATED) {
            throw new BusinessRuleException("DELIVERABLE_ALREADY_VALIDATED", "Cannot upload new version to an already validated deliverable.");
        }

        User uploader = findUserOrThrow(actor.getId());
        FileAsset fileAsset = storeFileAsset(file, uploader, DocumentType.STEG_INTERNSHIP_REPORT);

        int nextVersion = (deliverable.getCurrentVersion() != null ? deliverable.getCurrentVersion() : 1) + 1;
        deliverable.setCurrentVersion(nextVersion);
        deliverable = deliverableRepository.save(deliverable);

        DeliverableVersion version = new DeliverableVersion(deliverable, fileAsset, uploader, nextVersion, changeSummary);
        deliverableVersionRepository.save(version);

        log.info("Deliverable {} new version {} uploaded by {}", deliverableId, nextVersion, uploader.getId());
        return toDeliverableResponse(deliverable);
    }

    @Transactional(readOnly = true)
    public List<DeliverableVersionResponse> listDeliverableVersions(UUID deliverableId) {
        deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));
        return deliverableVersionRepository.findByDeliverableIdOrderByVersionNumberAsc(deliverableId).stream()
                .map(this::toDeliverableVersionResponse)
                .toList();
    }

    @Transactional
    public DeliverableResponse submitDeliverable(UUID deliverableId, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        if (deliverable.getStatus() != DeliverableStatus.DRAFT && deliverable.getStatus() != DeliverableStatus.REJECTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only DRAFT or REJECTED deliverables can be submitted. Current status: " + deliverable.getStatus());
        }

        deliverable.setStatus(DeliverableStatus.SUBMITTED);
        deliverable.setSubmittedAt(Instant.now());
        deliverable = deliverableRepository.save(deliverable);
        log.info("Deliverable submitted: id={}", deliverableId);
        return toDeliverableResponse(deliverable);
    }

    @Transactional
    public DeliverableResponse validateDeliverable(UUID deliverableId, ValidationRequest request, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        if (deliverable.getStatus() != DeliverableStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only SUBMITTED deliverables can be validated. Current status: " + deliverable.getStatus());
        }

        Employee supervisor = findSupervisorForInternship(deliverable.getInternship().getId(), actor);

        deliverable.setStatus(DeliverableStatus.VALIDATED);
        deliverable.setValidatedBy(supervisor);
        deliverable.setValidatedAt(Instant.now());
        deliverable = deliverableRepository.save(deliverable);
        log.info("Deliverable validated: id={}, validatedBy={}", deliverableId, supervisor != null ? supervisor.getId() : null);
        return toDeliverableResponse(deliverable);
    }

    @Transactional
    public DeliverableResponse rejectDeliverable(UUID deliverableId, ValidationRequest request, UserPrincipal actor) {
        Deliverable deliverable = deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        if (deliverable.getStatus() != DeliverableStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION",
                    "Only SUBMITTED deliverables can be rejected. Current status: " + deliverable.getStatus());
        }

        Employee supervisor = findSupervisorForInternship(deliverable.getInternship().getId(), actor);

        deliverable.setStatus(DeliverableStatus.REJECTED);
        deliverable.setValidatedBy(supervisor);
        deliverable.setValidatedAt(Instant.now());
        deliverable = deliverableRepository.save(deliverable);
        log.info("Deliverable rejected: id={}, rejectedBy={}", deliverableId, supervisor != null ? supervisor.getId() : null);
        return toDeliverableResponse(deliverable);
    }

    @Transactional(readOnly = true)
    public DocumentService.DownloadStream downloadDeliverableVersion(UUID deliverableId, Integer versionNumber, UserPrincipal actor) {
        deliverableRepository.findById(deliverableId)
                .orElseThrow(() -> new ResourceNotFoundException("Deliverable not found: " + deliverableId));

        DeliverableVersion version;
        if (versionNumber != null) {
            version = deliverableVersionRepository.findByDeliverableIdAndVersionNumber(deliverableId, versionNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("Deliverable version not found: v" + versionNumber));
        } else {
            version = deliverableVersionRepository.findTopByDeliverableIdOrderByVersionNumberDesc(deliverableId)
                    .orElseThrow(() -> new ResourceNotFoundException("No versions found for deliverable: " + deliverableId));
        }

        FileAsset fa = version.getFile();
        InputStream is = fileStorageService.getInputStream(fa.getStorageKey());
        return new DocumentService.DownloadStream(is, fa.getOriginalFileName(), fa.getMimeType(), fa.getSize());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private InternshipJournal findOrCreateJournal(UUID internshipId) {
        Internship internship = findInternshipOrThrow(internshipId);
        return journalRepository.findByInternshipId(internshipId)
                .orElseGet(() -> journalRepository.save(new InternshipJournal(internship)));
    }

    private Internship findInternshipOrThrow(UUID internshipId) {
        return internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private Employee findSupervisorForInternship(UUID internshipId, UserPrincipal actor) {
        InternshipAssignment assignment = assignmentRepository
                .findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE)
                .orElse(null);

        if (assignment != null && assignment.getSupervisor() != null) {
            return assignment.getSupervisor();
        }

        return employeeRepository.findByUserId(actor.getId()).orElse(null);
    }

    private FileAsset storeFileAsset(MultipartFile file, User uploader, DocumentType docType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("EMPTY_FILE", "Uploaded deliverable file cannot be empty.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("FILE_READ_ERROR", "Could not read deliverable file: " + e.getMessage());
        }

        DocumentValidationService.ValidationResult validation =
                documentValidationService.validate(bytes, file.getOriginalFilename(), file.getContentType(), docType);

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            if (!malwareScanner.isClean(is, file.getOriginalFilename())) {
                throw new BusinessRuleException("MALWARE_DETECTED", "Malware or suspicious content detected in deliverable file.");
            }
        } catch (IOException e) {
            log.error("Malware scanner error: {}", e.getMessage());
        }

        String storageKey;
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            storageKey = fileStorageService.store(is, file.getOriginalFilename(), validation.detectedMimeType());
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist deliverable binary", e);
        }

        FileAsset fileAsset = new FileAsset(
                storageKey,
                file.getOriginalFilename(),
                validation.checksum(),
                validation.detectedMimeType(),
                validation.sizeBytes(),
                uploader
        );
        return fileAssetRepository.save(fileAsset);
    }

    private DeliverableResponse toDeliverableResponse(Deliverable deliverable) {
        DeliverableVersion latest = deliverableVersionRepository.findTopByDeliverableIdOrderByVersionNumberDesc(deliverable.getId()).orElse(null);
        DeliverableVersionResponse latestDto = latest != null ? toDeliverableVersionResponse(latest) : null;
        return DeliverableResponse.from(deliverable, latestDto);
    }

    private DeliverableVersionResponse toDeliverableVersionResponse(DeliverableVersion v) {
        String uploadedByEmail = v.getUploadedBy() != null ? v.getUploadedBy().getEmail() : null;
        FileAsset fa = v.getFile();
        return new DeliverableVersionResponse(
                v.getId(),
                v.getDeliverable() != null ? v.getDeliverable().getId() : null,
                fa != null ? fa.getId() : null,
                fa != null ? fa.getOriginalFileName() : null,
                fa != null ? fa.getMimeType() : null,
                fa != null ? fa.getSize() : null,
                v.getUploadedBy() != null ? v.getUploadedBy().getId() : null,
                uploadedByEmail,
                v.getVersionNumber(),
                v.getChangeSummary(),
                v.getUploadedAt()
        );
    }
}
