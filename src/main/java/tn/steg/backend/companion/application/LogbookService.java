package tn.steg.backend.companion.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Logbook;
import tn.steg.backend.companion.domain.model.LogbookStatus;
import tn.steg.backend.companion.domain.repository.LogbookRepository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for Logbook lifecycle.
 * <p>
 * States: DRAFT → SUBMITTED → VALIDATED/REJECTED → OFFICIAL
 * Only the intern can submit for validation. Only the supervisor can validate/reject.
 * Once OFFICIAL, the logbook is immutable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogbookService {

    private final LogbookRepository logbookRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    /**
     * Intern submits their edited logbook draft for supervisor validation.
     * Creates or updates a logbook in SUBMITTED state.
     */
    @Transactional
    public Logbook submitForValidation(UUID internshipId, String finalText, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);

        if (finalText == null || finalText.trim().isEmpty()) {
            throw new BusinessRuleException("LOGBOOK_EMPTY",
                    "The reviewed logbook text is empty.");
        }

        // Only the assigned intern can submit their own logbook.
        if (!isInternOf(internship, actor)) {
            throw new BusinessRuleException("UNAUTHORIZED",
                    "Only the assigned intern can submit the logbook for validation.");
        }

        if (internship.getStatus() != InternshipStatus.COMPLETED) {
            throw new BusinessRuleException("INTERNSHIP_NOT_COMPLETED",
                    "Logbook can only be submitted for completed internships.");
        }

        Optional<Logbook> existing = logbookRepository.findByInternshipId(internshipId);
        Logbook logbook;
        if (existing.isPresent()) {
            switch (existing.get().getStatus()) {
                case SUBMITTED, VALIDATED, OFFICIAL -> throw new BusinessRuleException("LOGBOOK_ALREADY_SUBMITTED",
                        "A logbook has already been submitted for this internship.");
                case REJECTED, DRAFT -> {
                    logbook = existing.get();
                    logbook.setStatus(LogbookStatus.SUBMITTED);
                    logbook.setFinalText(finalText.trim());
                    logbook.setSubmittedAt(Instant.now());
                    logbook.setSubmittedBy(findUserOrThrow(actor.getId()));
                }
                default -> throw new BusinessRuleException("INVALID_STATUS", "Unexpected logbook status.");
            }
        } else {
            logbook = new Logbook(null, internship, findUserOrThrow(actor.getId()), null);
            logbook.setStatus(LogbookStatus.SUBMITTED);
            logbook.setFinalText(finalText.trim());
            logbook.setSubmittedAt(Instant.now());
        }

        logbook = logbookRepository.save(logbook);

        auditService.log("LOGBOOK_SUBMITTED", "Logbook", logbook.getId(), null,
                Map.of("internshipId", internshipId, "status", "SUBMITTED"),
                actor.getId(), null);

        log.info("Logbook submitted for validation: internship={}, logbook={}", internshipId, logbook.getId());

        return logbook;
    }

    @Transactional
    public Logbook validate(UUID logbookId, UserPrincipal actor) {
        Logbook logbook = findLogbookOrThrow(logbookId);

        if (!hasSupervisorRole(logbook.getInternship(), actor)) {
            throw new BusinessRuleException("UNAUTHORIZED",
                    "Only the assigned supervisor can validate this logbook.");
        }

        if (logbook.getStatus() != LogbookStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only logbooks in SUBMITTED state can be validated. Current: " + logbook.getStatus());
        }

        logbook.setStatus(LogbookStatus.VALIDATED);
        logbook.setValidatedAt(Instant.now());
        logbook.setValidatedBy(findUserOrThrow(actor.getId()));
        logbook = logbookRepository.save(logbook);

        auditService.log("LOGBOOK_VALIDATED", "Logbook", logbookId, null,
                Map.of("status", "VALIDATED"),
                actor.getId(), null);

        log.info("Logbook validated: {}", logbookId);
        return logbook;
    }

    @Transactional
    public Logbook reject(UUID logbookId, String reason, UserPrincipal actor) {
        Logbook logbook = findLogbookOrThrow(logbookId);

        if (!hasSupervisorRole(logbook.getInternship(), actor)) {
            throw new BusinessRuleException("UNAUTHORIZED",
                    "Only the assigned supervisor can reject this logbook.");
        }

        if (logbook.getStatus() != LogbookStatus.SUBMITTED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only logbooks in SUBMITTED state can be rejected. Current: " + logbook.getStatus());
        }

        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessRuleException("REJECTION_REASON_REQUIRED",
                    "A rejection reason is required.");
        }

        logbook.setStatus(LogbookStatus.REJECTED);
        logbook.setRejectionReason(reason.trim());
        logbook = logbookRepository.save(logbook);

        auditService.log("LOGBOOK_REJECTED", "Logbook", logbookId, null,
                Map.of("reason", reason),
                actor.getId(), null);

        log.info("Logbook rejected: {}", logbookId);
        return logbook;
    }

    /**
     * HR/ADMIN finalizes a VALIDATED logbook: it becomes OFFICIAL and the
     * lifecycle is complete (immutable thereafter). Backend-enforced rule:
     * only VALIDATED logbooks may be promoted.
     */
    @Transactional
    public Logbook promoteToOfficial(UUID logbookId, UserPrincipal actor) {
        Logbook logbook = findLogbookOrThrow(logbookId);

        if (!actor.hasRole("ADMIN") && !actor.hasRole("HR")) {
            throw new BusinessRuleException("UNAUTHORIZED",
                    "Only an HR/ADMIN user can finalize a logbook as official.");
        }

        if (logbook.getStatus() != LogbookStatus.VALIDATED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Only VALIDATED logbooks can be made official. Current: " + logbook.getStatus());
        }

        logbook.setStatus(LogbookStatus.OFFICIAL);
        logbook = logbookRepository.save(logbook);

        auditService.log("LOGBOOK_OFFICIALIZED", "Logbook", logbookId, null,
                Map.of("status", "OFFICIAL"),
                actor.getId(), null);

        log.info("Logbook made official: {}", logbookId);
        return logbook;
    }

    @Transactional(readOnly = true)
    public Logbook getByInternship(UUID internshipId) {
        return logbookRepository.findByInternshipId(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Logbook not found for internship: " + internshipId));
    }

    @Transactional(readOnly = true)
    public Optional<Logbook> findByInternshipIdAndStatus(UUID internshipId, LogbookStatus status) {
        return logbookRepository.findByInternshipIdAndStatus(internshipId, status);
    }

    private boolean isInternOf(Internship internship, UserPrincipal actor) {
        return internship.getCandidate() != null
                && internship.getCandidate().getUser() != null
                && internship.getCandidate().getUser().getId().equals(actor.getId());
    }

    private boolean hasSupervisorRole(Internship internship, UserPrincipal actor) {
        if (actor.hasRole("ADMIN") || actor.hasRole("HR")) {
            return true;
        }
        return assignmentRepository.findByInternshipIdAndStatus(
                        internship.getId(), AssignmentStatus.ACTIVE)
                .map(a -> a.getSupervisor() != null
                        && a.getSupervisor().getUser() != null
                        && a.getSupervisor().getUser().getId().equals(actor.getId()))
                .orElse(false);
    }

    private Internship findInternshipOrThrow(UUID id) {
        return internshipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + id));
    }

    private Logbook findLogbookOrThrow(UUID id) {
        return logbookRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Logbook not found: " + id));
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }
}