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
import tn.steg.backend.certificate.domain.repository.CertificateRepository;
import tn.steg.backend.common.domain.event.CertificateAvailableEvent;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
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
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final FileAssetRepository fileAssetRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final FileStorageService fileStorageService;
    private final AuditService auditService;
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
     * Generates the internship certificate. The internship must be COMPLETED;
     * the caller must be its ACTIVE supervisor (ADMIN/HR override at the
     * controller level) with a linked employee profile for attribution.
     */
    @Transactional
    public CertificateResponse generateCertificate(UUID internshipId, UserPrincipal actor) {
        // Internship row lock first: concurrent generations for the same
        // internship serialize, so the existence check below is race-free.
        Internship internship = internshipRepository.findByIdForUpdate(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));
        if (internship.getStatus() != InternshipStatus.COMPLETED) {
            throw new BusinessRuleException("INTERNSHIP_NOT_COMPLETED",
                    "Certificates can only be generated for COMPLETED internships. Current: " + internship.getStatus());
        }
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
        String supervisorName = assignment.map(a -> a.getSupervisor().getFirstName() + " " + a.getSupervisor().getLastName())
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
        createLinkedDocument(fileAsset, DocumentType.INTERNSHIP_CERTIFICATE);

        Certificate certificate = new Certificate(reference, internship, generator, fileAsset,
                templateCode, templateVersion, issueDate);
        certificate = certificateRepository.save(certificate);

        auditService.log("CERTIFICATE_GENERATED", "Certificate", certificate.getId(),
                null, Map.of("reference", reference, "internship", internship.getReference()),
                actor.getId(), null);
        log.info("Certificate generated: ref={} internship={} actor={}", reference, internship.getReference(), actor.getId());

        eventPublisher.publishEvent(new CertificateAvailableEvent(
                certificate.getId(), reference, internship.getId(),
                internship.getCandidate().getUser().getId(), actor.getId()));

        return CertificateResponse.from(certificate);
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    /**
     * Streams the certificate PDF. Allowed for staff (ADMIN/FINANCE/HR), the
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
    // Helpers
    // ------------------------------------------------------------------

    private void assertCanDownload(Certificate certificate, UserPrincipal actor) {
        if (actor.hasRole("ADMIN") || actor.hasRole("FINANCE") || actor.hasRole("HR")) {
            return;
        }
        UUID internshipId = certificate.getInternship().getId();
        boolean supervisor = assignmentRepository.findByInternshipIdAndStatus(internshipId, AssignmentStatus.ACTIVE)
                .map(a -> a.getSupervisor() != null && a.getSupervisor().getUser() != null
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

    private void createLinkedDocument(FileAsset fileAsset, DocumentType type) {
        String reference = nextDocumentReference();
        Document document = documentRepository.save(new Document(reference, type));
        documentVersionRepository.save(new DocumentVersion(
                document, fileAsset, 1, fileAsset.getOriginalFileName(), fileAsset.getChecksum()));
    }

    private String nextReference(String prefix) {
        int year = Year.now().getValue();
        String base = prefix + year + "-";
        long sequence = certificateRepository.countByReferencePrefix(base) + 1;
        String reference;
        do {
            reference = String.format("%s%05d", base, sequence);
            sequence++;
        } while (certificateRepository.existsByReference(reference));
        return reference;
    }

    private String nextDocumentReference() {
        int year = Year.now().getValue();
        String prefix = "DOC-" + year + "-";
        return String.format("%s%05d", prefix, documentRepository.countByReferencePrefix(prefix) + 1);
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
