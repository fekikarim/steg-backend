package tn.steg.backend.internship.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.audit.domain.model.AuditSource;
import tn.steg.backend.common.application.idempotency.IdempotencyService;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.internship.application.dto.DocumentPreparationRequest;
import tn.steg.backend.internship.application.dto.DocumentPreparationResponse;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.notification.application.NotificationService;
import tn.steg.backend.notification.domain.model.NotificationPriority;
import tn.steg.backend.notification.domain.model.NotificationType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static tn.steg.backend.common.domain.util.NullSafe.listOf;

/**
 * T14/D14 — supervisor asks his own students to prepare validation
 * documents (SU-HOME-02, SU-CAL-04).
 *
 * <p>Scope is {@code SupervisionScopeService} for every caller, ADMIN
 * included (D1b/BR-03): an out-of-scope internship id is 404 (BR-04, no
 * existence leak beyond the id the caller already forged), and an empty
 * selection is refused. One {@code DOCUMENTS_PREPARATION_REQUESTED}
 * notification per covered internship (exactly one recipient each — the
 * intern), delivered over the existing realtime path with the intern home
 * as the deep-link target (the action hub for journal + report
 * preparation). Double submits with the same {@code X-Idempotency-Key}
 * replay the stored receipt instead of notifying twice. Every covered
 * internship is audited (metadata only — BR-59: no personal data in logs).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPreparationService {

    private final SupervisionScopeService supervisionScopeService;
    private final InternshipRepository internshipRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public DocumentPreparationResponse notifyDocumentsPreparation(
            UserPrincipal actor, DocumentPreparationRequest request) {
        return idempotencyService.execute(actor.getId(),
                IdempotencyService.currentKey().orElse(null),
                () -> executeNotify(actor, request),
                DocumentPreparationResponse.class);
    }

    private DocumentPreparationResponse executeNotify(
            UserPrincipal actor, DocumentPreparationRequest request) {
        List<UUID> ids = request.internshipIds() == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(request.internshipIds()));
        if (ids.isEmpty()) {
            throw new BusinessRuleException("DOCUMENT_PREPARATION_EMPTY",
                    "Select at least one of your students to notify.");
        }

        List<UUID> covered = new ArrayList<>();
        // Pass 1 — resolve + scope-check everything first: an out-of-scope
        // id aborts the whole batch (404) before anyone is notified.
        List<Internship> targets = new ArrayList<>();
        for (UUID internshipId : ids) {
            Internship internship = internshipRepository.findById(internshipId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Internship not found: " + internshipId));
            if (!supervisionScopeService.isAssignedTo(actor, internshipId)) {
                throw new ResourceNotFoundException(
                        "Internship not found: " + internshipId);
            }
            targets.add(internship);
        }
        // Pass 2 — notify + audit per covered internship.
        for (Internship internship : targets) {
            UUID internshipId = internship.getId();
            UUID internUserId = internship.getCandidate() != null
                    && internship.getCandidate().getUser() != null
                    ? internship.getCandidate().getUser().getId()
                    : null;
            if (internUserId == null) {
                throw new BusinessRuleException("DOCUMENT_PREPARATION_NO_INTERN",
                        "This internship has no linked intern account to notify.");
            }
            notificationService.dispatch(NotificationType.DOCUMENTS_PREPARATION_REQUESTED,
                    "Prepare your validation documents",
                    "Your supervisor asks you to prepare your internship journal and report "
                            + "for validation (" + internship.getReference() + "). "
                            + "Open your home screen to upload them.",
                    NotificationPriority.NORMAL,
                    "Internship", internshipId,
                    listOf(internUserId), actor.getId());
            // §8.2/BR-55: this is a MOBILE-originated action (the supervisor
            // acts from the STEG intern app, D14) — record the real source so
            // the audit page can tell it apart from back-office work. Payload
            // is metadata only (BR-59: no personal data).
            auditService.log("DOCUMENT_PREPARATION_REQUESTED", "Internship", internshipId,
                    null, java.util.Map.of("reference", internship.getReference()),
                    actor.getId(), null, AuditService.primaryRole(actor.getRoles()), null,
                    AuditSource.MOBILE);
            covered.add(internshipId);
        }
        log.info("Document preparation requested: supervisor={}, internships={}",
                actor.getId(), covered.size());
        return new DocumentPreparationResponse(covered.size(), List.copyOf(covered));
    }
}
