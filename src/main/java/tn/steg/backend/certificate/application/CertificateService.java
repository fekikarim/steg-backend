package tn.steg.backend.certificate.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.certificate.application.dto.CertificateResponse;
import tn.steg.backend.certificate.domain.model.Certificate;
import tn.steg.backend.certificate.domain.model.CertificateStatus;
import tn.steg.backend.certificate.domain.model.CertificateVersion;
import tn.steg.backend.certificate.domain.repository.CertificateRepository;
import tn.steg.backend.certificate.domain.repository.CertificateVersionRepository;
import tn.steg.backend.common.application.idempotency.IdempotencyService;
import tn.steg.backend.common.domain.event.CertificateAvailableEvent;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ConflictException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.domain.model.Document;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.DocumentVersion;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.domain.repository.DocumentRepository;
import tn.steg.backend.document.domain.repository.DocumentVersionRepository;
import tn.steg.backend.document.domain.repository.FileAssetRepository;
import tn.steg.backend.document.domain.service.FileStorageService;
import tn.steg.backend.document.domain.service.OfficialBrandingProvider;
import tn.steg.backend.document.domain.service.PdfDocumentRenderer;
import tn.steg.backend.document.domain.service.PdfTemplateProvider;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Application service for internship certificates (Phase A11).
 *
 * <p>Certificates are generated server-side on demand as official PDFs:
 * {@code generationServerDate} is always {@link Instant#now()} captured here —
 * no endpoint accepts a client-supplied date, so spoofing is structurally
 * impossible. Each certificate persists as its own aggregate plus a
 * {@link FileAsset} (and a parallel {@link Document} row of type
 * INTERNSHIP_CERTIFICATE so it can be attached to finance dossiers and handled
 * uniformly). Certificates are never the same object as payment receipts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CertificateRepository certificateRepository;
    private final CertificateVersionRepository certificateVersionRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final FileAssetRepository fileAssetRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final FileStorageService fileStorageService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;
    private final PdfTemplateProvider templateProvider;
    private final OfficialBrandingProvider brandingProvider;
    private final PdfDocumentRenderer pdfRenderer;

    @Value("${steg.documents.certificate.template-code:STEG-INTERNSHIP-CERTIFICATE}")
    private String templateCode;

    @Value("${steg.documents.certificate.template-version:1}")
    private int templateVersion;

    @Value("${steg.documents.generation-location:Tunis}")
    private String generationLocation;

    public record DownloadStream(InputStream inputStream, String fileName, String mimeType, long size) {
    }

    // ------------------------------------------------------------------
    // Generation ("Export PDF")
    // ------------------------------------------------------------------

    /**
     * Generates the internship certificate. The internship must be VALIDATED
     * (audit assumption #17 — stricter than AGENTS.md §5.7's "approved": the
     * §5.11 manual validation must have completed first; S7's decision
     * endpoint is what produces VALIDATED, so the flow stays reachable); the
     * caller must be ADMIN (enforced at the controller level) with a linked
     * employee profile for attribution. Supervisors cannot generate
     * certificates: generation is an administrative act, not a supervision act.
     */
    @Transactional
    public CertificateResponse generateCertificate(UUID internshipId, UserPrincipal actor) {
        // E1.6: duplicate submissions (double-click, mobile retry) replay the stored
        // response instead of generating a second certificate.
        return idempotencyService.execute(actor.getId(),
                IdempotencyService.currentKey().orElse(null),
                () -> doGenerateCertificate(internshipId, actor), CertificateResponse.class);
    }

    private CertificateResponse doGenerateCertificate(UUID internshipId, UserPrincipal actor) {
        // Internship row lock first: concurrent generations for the same
        // internship serialize, so the existence check below is race-free.
        Internship internship = internshipRepository.findByIdForUpdate(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));
        if (internship.getStatus() != InternshipStatus.VALIDATED) {
            throw new ConflictException("INTERNSHIP_NOT_VALIDATED",
                    "Certificates can only be generated for VALIDATED internships. Current: " + internship.getStatus());
        }
        // S6b: the certificate gate is the explicit §4 status. The deleted
        // legacy workflow engine used to require a VALIDATION workflow action;
        // that table is gone by design (single authority), so no second gate
        // is checked here. HOW an internship becomes VALIDATED is restricted
        // in S7 (manual per-document decision + receipt generation).
        // Explicit policy: exactly one valid certificate per internship. Repeat
        // requests get the existing reference (409) instead of silent duplicates;
        // the V23 partial unique index backstops the race that remains.
        Optional<Certificate> existing = certificateRepository.findByInternshipId(internshipId).stream()
                .filter(c -> c.getStatus() != CertificateStatus.REVOKED)
                .findFirst();
        if (existing.isPresent()) {
            throw new BusinessRuleException("CERTIFICATE_ALREADY_EXISTS",
                    "A valid certificate already exists for this internship: "
                            + existing.get().getReference());
        }

        Employee generator = employeeRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new AccessDeniedException("A linked employee profile is required to generate certificates."));
        User actorUser = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + actor.getId()));

        Optional<InternshipAssignment> assignment =
                assignmentRepository.findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE);
        String department = assignment.map(a -> a.getDestination().getName()).orElse("—");
        String supervisorName = assignment.map(a -> a.getSupervisor() != null
                ? a.getSupervisor().getFirstName() + " " + a.getSupervisor().getLastName()
                : a.getSupervisorUser() != null ? a.getSupervisorUser().getEmail() : null)
            .filter(name -> name != null && !name.isBlank())
            .orElse(generator.getFirstName() + " " + generator.getLastName());

        String reference = nextReference("CERT-");
        LocalDate issueDate = LocalDate.now();
        // NOTE: nationalId is sensitive (CIN). It is used here because the
        // official template requires it, never logged, and the resulting PDF
        // inherits restricted download + access auditing.
        Map<String, String> values = Map.ofEntries(
                Map.entry("internFullName", fullName(internship)),
                Map.entry("nationalId", nationalId(internship)),
                Map.entry("internshipType", prettyType(internship)),
                Map.entry("university", university(internship)),
                Map.entry("educationLevel", educationLevel(internship)),
                Map.entry("department", department),
                Map.entry("supervisorFullName", supervisorName),
                Map.entry("subject", internship.getSubject() != null && !internship.getSubject().isBlank()
                        ? internship.getSubject() : "—"),
                Map.entry("internshipReference", internship.getReference()),
                Map.entry("certificateReference", reference),
                Map.entry("internshipPeriod", "du " + internship.getStartDate().format(DATE_FORMAT)
                        + " au " + internship.getEndDate().format(DATE_FORMAT)),
                Map.entry("generationLocation", generationLocation),
                Map.entry("generationDate", issueDate.format(DATE_FORMAT)),
                Map.entry("issueDate", issueDate.format(DATE_FORMAT)));

        PdfTemplateProvider.StructuredTemplate template =
                templateProvider.resolveStructured("certificate-template", values);
        byte[] pdf = pdfRenderer.renderStructured(template.title(), template.subtitle(),
                toBlocks(template), template.footer(), brandingProvider.getLogoPngBytes());

        FileAsset fileAsset = storeGeneratedPdf(pdf, "certificat-" + reference + ".pdf", actorUser);
        createLinkedDocument(fileAsset, DocumentType.INTERNSHIP_CERTIFICATE, reference, 1);

        Certificate certificate = new Certificate(reference, internship, generator, fileAsset,
                templateCode, templateVersion, issueDate);
        certificate = certificateRepository.save(certificate);
        certificateVersionRepository.save(new CertificateVersion(certificate, 1, fileAsset));

        auditService.log("CERTIFICATE_GENERATED", "Certificate", certificate.getId(),
                null, Map.of("reference", reference, "internship", internship.getReference()),
                actor.getId(), null);
        log.info("Certificate generated: ref={} internship={} actor={}", reference, internship.getReference(), actor.getId());

        // A staff-created profile may have NO account yet — the intern user id
        // is then null and the listener (NullSafe.listOf) simply skips the
        // notification instead of NPE-ing the generation transaction.
        eventPublisher.publishEvent(new CertificateAvailableEvent(
                certificate.getId(), reference, internship.getId(),
                internship.getCandidate().getUser() != null
                        ? internship.getCandidate().getUser().getId() : null,
                actor.getId()));

        return CertificateResponse.from(certificate);
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    /**
     * Streams the certificate PDF. Allowed for staff (ADMIN), the
     * internship's ACTIVE supervisor, and the intern themselves. Access is audited.
     */
    @Transactional
    public DownloadStream downloadCertificate(UUID certificateId, UserPrincipal actor, String ipAddress) {
        Certificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate not found: " + certificateId));
        assertCanDownload(certificate, actor);

        auditService.log("CERTIFICATE_DOWNLOADED", "Certificate", certificateId,
                null, null, actor.getId(), ipAddress);

        FileAsset fileAsset = certificate.getPdfFile();
        InputStream stream = fileStorageService.getInputStream(fileAsset.getStorageKey());
        return new DownloadStream(stream, fileAsset.getOriginalFileName(), fileAsset.getMimeType(), fileAsset.getSize());
    }

    // ------------------------------------------------------------------
    // S8 workspace (AGENTS.md §5.7, Admin only)
    // ------------------------------------------------------------------

    /**
     * S8 certificate list: one server page (search + status filter run in the
     * database). Admin only — a Supervisor gets 403, never a scoped list,
     * because certificates are an administrative act (§3.3).
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<
            tn.steg.backend.certificate.application.dto.CertificateRowResponse> searchCertificates(
            String q, CertificateStatus status,
            org.springframework.data.domain.Pageable pageable, UserPrincipal actor) {
        requireAdmin(actor);
        String pattern = q == null || q.isBlank() ? null
                : "%" + q.strip().toLowerCase().replace("%", "") + "%";
        int size = Math.min(Math.max(pageable.getPageSize(), 1), 100);
        org.springframework.data.domain.Pageable sane = org.springframework.data.domain.PageRequest.of(
                Math.max(pageable.getPageNumber(), 0), size, sanitizeSort(pageable.getSort()));
        return certificateRepository.searchCertificates(status, pattern, sane)
                .map(tn.steg.backend.certificate.application.dto.CertificateRowResponse::from);
    }

    /** S8 detail: the certificate plus its append-only version history. */
    @Transactional(readOnly = true)
    public tn.steg.backend.certificate.application.dto.CertificateDetailResponse getCertificate(
            UUID certificateId, UserPrincipal actor) {
        requireAdmin(actor);
        Certificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate not found: " + certificateId));
        List<CertificateVersion> versions = certificateVersionRepository
                .findByCertificateIdOrderByVersionNumberAsc(certificateId);
        return tn.steg.backend.certificate.application.dto.CertificateDetailResponse.from(
                certificate, versions);
    }

    /**
     * S8 edit-and-regenerate: the certificate data is derived (name, type,
     * university, period come from the internship), so "editing" means
     * re-issuing the PDF — optionally with a corrected issue date — as a NEW
     * version row. The reference stays stable; no issued PDF is overwritten.
     * Revoked certificates cannot be regenerated (generate anew instead).
     */
    @Transactional
    public tn.steg.backend.certificate.application.dto.CertificateDetailResponse regenerateCertificate(
            UUID certificateId, LocalDate issueDate, UserPrincipal actor) {
        requireAdmin(actor);
        Certificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate not found: " + certificateId));
        if (certificate.getStatus() == CertificateStatus.REVOKED) {
            throw new ConflictException("CERTIFICATE_REVOKED",
                    "A revoked certificate cannot be regenerated. Generate a new one instead: "
                            + certificate.getReference());
        }
        Internship internship = certificate.getInternship();
        Employee generator = employeeRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new AccessDeniedException("A linked employee profile is required to generate certificates."));
        User actorUser = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + actor.getId()));

        LocalDate effectiveIssueDate = issueDate != null ? issueDate : certificate.getIssueDate();
        byte[] pdf = renderCertificatePdf(internship, certificate.getReference(), effectiveIssueDate, generator);
        FileAsset fileAsset = storeGeneratedPdf(pdf, "certificat-" + certificate.getReference()
                + "-v" + (certificate.getVersionNumber() + 1) + ".pdf", actorUser);
        int newVersion = certificate.getVersionNumber() + 1;
        createLinkedDocument(fileAsset, DocumentType.INTERNSHIP_CERTIFICATE,
                certificate.getReference(), newVersion);

        certificate.setPdfFile(fileAsset);
        certificate.setIssueDate(effectiveIssueDate);
        certificate.setVersionNumber(newVersion);
        certificate = certificateRepository.save(certificate);
        certificateVersionRepository.save(new CertificateVersion(certificate, newVersion, fileAsset));

        auditService.log("CERTIFICATE_REGENERATED", "Certificate", certificate.getId(),
                Map.of("version", newVersion - 1),
                Map.of("version", newVersion, "reference", certificate.getReference()),
                actor.getId(), null);
        log.info("Certificate regenerated: ref={} version={} actor={}",
                certificate.getReference(), newVersion, actor.getId());
        return getCertificate(certificate.getId(), actor);
    }

    /**
     * S8 soft delete: the row stays (audit trail + version history), the
     * status becomes REVOKED. A revoked certificate is invisible to the
     * generate-guard, so a fresh certificate can be generated afterwards.
     */
    @Transactional
    public void revokeCertificate(UUID certificateId, UserPrincipal actor) {
        requireAdmin(actor);
        Certificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate not found: " + certificateId));
        if (certificate.getStatus() == CertificateStatus.REVOKED) {
            throw new ConflictException("CERTIFICATE_ALREADY_REVOKED",
                    "Certificate is already revoked: " + certificate.getReference());
        }
        String statusBefore = certificate.getStatus().name();
        certificate.setStatus(CertificateStatus.REVOKED);
        certificate.setRevokedAt(Instant.now());
        certificateRepository.save(certificate);
        auditService.log("CERTIFICATE_REVOKED", "Certificate", certificate.getId(),
                Map.of("status", statusBefore),
                Map.of("status", CertificateStatus.REVOKED.name(), "reference", certificate.getReference()),
                actor.getId(), null);
        log.info("Certificate revoked: ref={} actor={}", certificate.getReference(), actor.getId());
    }

    /**
     * S8 versioned download: {@code version} selects a history entry,
     * defaulting to the latest issued PDF. Scope check unchanged.
     */
    @Transactional
    public DownloadStream downloadCertificate(
            UUID certificateId, Integer version, UserPrincipal actor, String ipAddress) {
        Certificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate not found: " + certificateId));
        assertCanDownload(certificate, actor);

        FileAsset fileAsset = certificate.getPdfFile();
        if (version != null) {
            fileAsset = certificateVersionRepository.findByCertificateIdOrderByVersionNumberAsc(certificateId)
                    .stream()
                    .filter(v -> version.equals(v.getVersionNumber()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Certificate version not found: " + certificate.getReference() + " v" + version))
                    .getPdfFile();
        }
        auditService.log("CERTIFICATE_DOWNLOADED", "Certificate", certificateId,
                Map.of("version", version != null ? version : certificate.getVersionNumber()),
                null, actor.getId(), ipAddress);
        InputStream stream = fileStorageService.getInputStream(fileAsset.getStorageKey());
        return new DownloadStream(stream, fileAsset.getOriginalFileName(), fileAsset.getMimeType(), fileAsset.getSize());
    }

    private void requireAdmin(UserPrincipal actor) {
        if (actor == null || !actor.hasRole("ADMIN")) {
            throw new AccessDeniedException("Certificate management is an Admin capability.");
        }
    }

    private org.springframework.data.domain.Sort sanitizeSort(org.springframework.data.domain.Sort sort) {
        Set<String> whitelist = Set.of("reference", "status", "issueDate", "generatedAt", "createdAt");
        List<org.springframework.data.domain.Sort.Order> orders = new ArrayList<>();
        for (org.springframework.data.domain.Sort.Order order : sort) {
            if (whitelist.contains(order.getProperty())) {
                orders.add(order);
            }
        }
        if (orders.isEmpty()) {
            return org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "createdAt");
        }
        return org.springframework.data.domain.Sort.by(orders);
    }

    /**
     * Shared PDF renderer (S8): generation and regeneration produce
     * byte-identical content for identical inputs — the version row, not the
     * bytes, is what distinguishes re-issues.
     */
    private byte[] renderCertificatePdf(
            Internship internship, String reference, LocalDate issueDate, Employee generator) {
        Optional<InternshipAssignment> assignment = assignmentRepository
                .findByInternshipIdAndStatus(internship.getId(), AssignmentStatus.ACTIVE);
        String department = assignment.map(a -> a.getDestination().getName()).orElse("—");
        String supervisorName = assignment.map(a -> a.getSupervisor() != null
                ? a.getSupervisor().getFirstName() + " " + a.getSupervisor().getLastName()
                : a.getSupervisorUser() != null ? a.getSupervisorUser().getEmail() : null)
            .filter(name -> name != null && !name.isBlank())
            .orElse(generator.getFirstName() + " " + generator.getLastName());

        Map<String, String> values = Map.ofEntries(
                Map.entry("internFullName", fullName(internship)),
                Map.entry("nationalId", nationalId(internship)),
                Map.entry("internshipType", prettyType(internship)),
                Map.entry("university", university(internship)),
                Map.entry("educationLevel", educationLevel(internship)),
                Map.entry("department", department),
                Map.entry("supervisorFullName", supervisorName),
                Map.entry("subject", internship.getSubject() != null && !internship.getSubject().isBlank()
                        ? internship.getSubject() : "—"),
                Map.entry("internshipReference", internship.getReference()),
                Map.entry("certificateReference", reference),
                Map.entry("internshipPeriod", "du " + internship.getStartDate().format(DATE_FORMAT)
                        + " au " + internship.getEndDate().format(DATE_FORMAT)),
                Map.entry("generationLocation", generationLocation),
                Map.entry("generationDate", issueDate.format(DATE_FORMAT)),
                Map.entry("issueDate", issueDate.format(DATE_FORMAT)));

        PdfTemplateProvider.StructuredTemplate template =
                templateProvider.resolveStructured("certificate-template", values);
        return pdfRenderer.renderStructured(template.title(), template.subtitle(),
                toBlocks(template), template.footer(), brandingProvider.getLogoPngBytes());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void assertCanDownload(Certificate certificate, UserPrincipal actor) {
        if (actor.hasRole("ADMIN")) {
            return;
        }
        UUID internshipId = certificate.getInternship().getId();
        boolean supervisor = assignmentRepository.findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE)
                .map(a -> a.getSupervisorUser() != null
                    ? a.getSupervisorUser().getId().equals(actor.getId())
                    : a.getSupervisor() != null && a.getSupervisor().getUser() != null
                        && a.getSupervisor().getUser().getId().equals(actor.getId()))
                .orElse(false);
        boolean owner = certificate.getInternship().getCandidate() != null
                && certificate.getInternship().getCandidate().getUser() != null
                && certificate.getInternship().getCandidate().getUser().getId().equals(actor.getId());
        if (!supervisor && !owner) {
            throw new AccessDeniedException("You are not authorized to download this certificate.");
        }
    }

    private String fullName(Internship internship) {
        if (internship.getCandidate() == null) {
            return "—";
        }
        return internship.getCandidate().getFirstName() + " " + internship.getCandidate().getLastName();
    }

    private String nationalId(Internship internship) {
        if (internship.getCandidate() == null || internship.getCandidate().getNationalIdEncrypted() == null
                || internship.getCandidate().getNationalIdEncrypted().isBlank()) {
            return "—";
        }
        return internship.getCandidate().getNationalIdEncrypted();
    }

    private String university(Internship internship) {
        if (internship.getCandidate() == null || internship.getCandidate().getUniversity() == null
                || internship.getCandidate().getUniversity().getName() == null) {
            return "—";
        }
        return internship.getCandidate().getUniversity().getName();
    }

    private String educationLevel(Internship internship) {
        if (internship.getAcademicLevel() == null || internship.getAcademicLevel().isBlank()) {
            return "—";
        }
        return internship.getAcademicLevel();
    }

    private List<PdfDocumentRenderer.ContentBlock> toBlocks(PdfTemplateProvider.StructuredTemplate template) {
        List<PdfDocumentRenderer.ContentBlock> blocks = new ArrayList<>();
        for (String paragraph : template.introParagraphs()) {
            blocks.add(new PdfDocumentRenderer.ContentBlock.Paragraph(paragraph));
        }
        if (!template.detailRows().isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (PdfTemplateProvider.TableRow row : template.detailRows()) {
                rows.add(List.of(row.label(), row.value()));
            }
            blocks.add(new PdfDocumentRenderer.ContentBlock.DetailsTable(List.of(), rows));
        }
        for (String paragraph : template.bodyParagraphs()) {
            blocks.add(new PdfDocumentRenderer.ContentBlock.Paragraph(paragraph));
        }
        return blocks;
    }

    private String prettyType(Internship internship) {
        if (internship.getType() == null) {
            return "—";
        }
        return switch (internship.getType()) {
            case OBSERVATION -> "d'observation";
            case PERFECTIONNEMENT -> "de perfectionnement";
            case PFE -> "de projet de fin d'études (PFE)";
        };
    }

    private FileAsset storeGeneratedPdf(byte[] pdf, String fileName, User uploadedBy) {
        String checksum = sha256Hex(pdf);
        String storageKey;
        try (InputStream in = new ByteArrayInputStream(pdf)) {
            storageKey = fileStorageService.store(in, fileName, "application/pdf");
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist generated PDF " + fileName, e);
        }
        // Generated bytes come from our own renderer + bundled logo (no untrusted
        // input), so upload-time Tika/malware validation does not apply here.
        return fileAssetRepository.save(
                new FileAsset(storageKey, fileName, checksum, "application/pdf", (long) pdf.length, uploadedBy));
    }

    private void createLinkedDocument(FileAsset fileAsset, DocumentType type,
                                        String certificateReference, int version) {
        // S8: derived from the (sequence-backed, unique) certificate reference
        // plus the version — deterministic and race-free, unlike the old
        // count-based document reference which collided under concurrency.
        String reference = "DOC-" + certificateReference + "-v" + version;
        Document document = documentRepository.save(new Document(reference, type));
        documentVersionRepository.save(new DocumentVersion(
                document, fileAsset, 1, fileAsset.getOriginalFileName(), fileAsset.getChecksum()));
    }

    /**
     * S8: sequence-backed allocation — atomic under concurrency, so parallel
     * generations for distinct internships always mint distinct references
     * (the old count + exists-check loop raced and died on the unique
     * constraint). The unique constraint stays as the backstop.
     */
    private String nextReference(String prefix) {
        int year = Year.now().getValue();
        return String.format("%s%d-%05d", prefix, year, certificateRepository.nextReferenceSequence());
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
