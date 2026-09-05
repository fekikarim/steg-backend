package tn.steg.backend.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tn.steg.backend.common.domain.event.DocumentVerifiedEvent;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.application.dto.ApplicationDocumentResponse;
import tn.steg.backend.document.application.dto.DocumentResponse;
import tn.steg.backend.document.application.dto.DocumentVerificationRequest;
import tn.steg.backend.document.application.dto.InternshipDocumentResponse;
import tn.steg.backend.document.domain.model.*;
import tn.steg.backend.document.domain.repository.*;
import tn.steg.backend.document.domain.service.DocumentValidationService;
import tn.steg.backend.document.domain.service.FileStorageService;
import tn.steg.backend.document.domain.service.MalwareScanner;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final FileStorageService fileStorageService;
    private final DocumentValidationService documentValidationService;
    private final MalwareScanner malwareScanner;
    private final DocumentRepository documentRepository;
    private final FileAssetRepository fileAssetRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final ApplicationDocumentRepository applicationDocumentRepository;
    private final InternshipDocumentRepository internshipDocumentRepository;
    private final InternshipApplicationRepository applicationRepository;
    private final InternshipRepository internshipRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    /** Business facts for cross-cutting concerns; never a dependency on consumers (Phase A10). */
    private final ApplicationEventPublisher eventPublisher;

    // -------------------------------------------------------------------------
    // Document Upload & Management
    // -------------------------------------------------------------------------

    @Transactional
    public DocumentResponse uploadDocument(MultipartFile file, DocumentType type, UserPrincipal uploader) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("EMPTY_FILE", "Uploaded file cannot be empty.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("FILE_READ_ERROR", "Could not read uploaded file content: " + e.getMessage());
        }

        // 1. Validate file (size limit, Tika inspection, spoof detection, SHA-256)
        DocumentValidationService.ValidationResult validation =
                documentValidationService.validate(bytes, file.getOriginalFilename(), file.getContentType(), type);

        // 2. Malware scan
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            if (!malwareScanner.isClean(is, file.getOriginalFilename())) {
                log.warn("Malware scanner flagged file '{}' uploaded by user '{}'", file.getOriginalFilename(), uploader.getId());
                throw new BusinessRuleException("MALWARE_DETECTED", "Malware or suspicious content detected in uploaded file.");
            }
        } catch (IOException e) {
            log.error("Malware scanner read failure: {}", e.getMessage());
        }

        // 3. Store file binary via FileStorageService
        String storageKey;
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            storageKey = fileStorageService.store(is, file.getOriginalFilename(), validation.detectedMimeType());
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist file", e);
        }

        User user = userRepository.findById(uploader.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + uploader.getId()));

        // 4. Create FileAsset entity
        FileAsset fileAsset = new FileAsset(
                storageKey,
                file.getOriginalFilename(),
                validation.checksum(),
                validation.detectedMimeType(),
                validation.sizeBytes(),
                user
        );
        fileAsset = fileAssetRepository.save(fileAsset);

        // 5. Create Document entity (with auto-generated reference DOC-YYYY-NNNNN)
        String reference = generateDocumentReference();
        Document document = new Document(reference, type);
        // Force restricted access for sensitive documents like CIN_COPY
        if (type == DocumentType.CIN_COPY) {
            document.setRestrictedAccess(true);
        }
        document = documentRepository.save(document);

        // 6. Create initial DocumentVersion (v1)
        DocumentVersion version = new DocumentVersion(
                document,
                fileAsset,
                1,
                file.getOriginalFilename(),
                validation.checksum()
        );
        version = documentVersionRepository.save(version);

        log.info("Document uploaded: ref={} type={} by user={}", reference, type, uploader.getId());
        return DocumentResponse.from(document, version, fileAsset);
    }

    @Transactional(readOnly = true)
    public DocumentResponse getDocumentMetadata(UUID documentId, UserPrincipal actor) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));

        DocumentVersion latestVersion = documentVersionRepository.findTopByDocumentIdOrderByVersionNumberDesc(documentId)
                .orElse(null);
        FileAsset fileAsset = latestVersion != null ? latestVersion.getFile() : null;

        return DocumentResponse.from(document, latestVersion, fileAsset);
    }

    public record DownloadStream(InputStream inputStream, String fileName, String mimeType, long size) {}

    @Transactional(readOnly = true)
    public DownloadStream getDownloadStream(UUID documentId, UserPrincipal actor) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));

        if (document.getRestrictedAccess() && !actor.hasRole("ADMIN")) {
            // Must use download-restricted endpoint
            throw new org.springframework.security.access.AccessDeniedException(
                    "This document has restricted access (CIN). Please use the restricted download endpoint with proper authorization.");
        }

        DocumentVersion latestVersion = documentVersionRepository.findTopByDocumentIdOrderByVersionNumberDesc(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("No version found for document: " + documentId));

        FileAsset fileAsset = latestVersion.getFile();
        InputStream is = fileStorageService.getInputStream(fileAsset.getStorageKey());

        return new DownloadStream(is, fileAsset.getOriginalFileName(), fileAsset.getMimeType(), fileAsset.getSize());
    }

    @Transactional
    public DownloadStream getRestrictedDownloadStream(UUID documentId, UserPrincipal actor, String ipAddress) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));

        // Synchronous audit logging before bytes are streamed
        auditService.log(
                "DOCUMENT_ACCESSED_RESTRICTED",
                "Document",
                documentId,
                null,
                null,
                actor.getId(),
                ipAddress
        );
        log.info("AUDIT: Restricted document {} accessed by actor {} from IP {}", documentId, actor.getId(), ipAddress);

        DocumentVersion latestVersion = documentVersionRepository.findTopByDocumentIdOrderByVersionNumberDesc(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("No version found for document: " + documentId));

        FileAsset fileAsset = latestVersion.getFile();
        InputStream is = fileStorageService.getInputStream(fileAsset.getStorageKey());

        return new DownloadStream(is, fileAsset.getOriginalFileName(), fileAsset.getMimeType(), fileAsset.getSize());
    }

    // -------------------------------------------------------------------------
    // Application Documents
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ApplicationDocumentResponse> getDocumentsForApplication(UUID applicationId, UserPrincipal actor) {
        // IDOR check on application
        InternshipApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + applicationId));

        if (actor.hasRole("CANDIDATE") && !app.getCandidate().getUser().getId().equals(actor.getId())) {
            throw new ResourceNotFoundException("Application not found or you do not have permission to view it.");
        }

        return applicationDocumentRepository.findByApplicationId(applicationId).stream()
                .map(ad -> {
                    DocumentVersion lv = documentVersionRepository.findTopByDocumentIdOrderByVersionNumberDesc(ad.getDocument().getId()).orElse(null);
                    FileAsset fa = lv != null ? lv.getFile() : null;
                    return ApplicationDocumentResponse.from(ad, DocumentResponse.from(ad.getDocument(), lv, fa));
                })
                .toList();
    }

    @Transactional
    public ApplicationDocumentResponse attachDocumentToApplication(UUID applicationId, UUID documentId, Boolean mandatory, UserPrincipal actor) {
        InternshipApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + applicationId));

        if (actor.hasRole("CANDIDATE") && !app.getCandidate().getUser().getId().equals(actor.getId())) {
            throw new ResourceNotFoundException("Application not found or you do not have permission to modify it.");
        }

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));

        ApplicationDocument appDoc = applicationDocumentRepository.findByApplicationIdAndDocumentId(applicationId, documentId)
                .orElseGet(() -> new ApplicationDocument(app, document, mandatory != null ? mandatory : true));

        appDoc.setMandatory(mandatory != null ? mandatory : true);
        appDoc = applicationDocumentRepository.save(appDoc);

        DocumentVersion lv = documentVersionRepository.findTopByDocumentIdOrderByVersionNumberDesc(document.getId()).orElse(null);
        FileAsset fa = lv != null ? lv.getFile() : null;
        return ApplicationDocumentResponse.from(appDoc, DocumentResponse.from(document, lv, fa));
    }

    @Transactional
    public ApplicationDocumentResponse verifyApplicationDocument(UUID applicationId, UUID documentId,
                                                                 DocumentVerificationRequest request, UserPrincipal actor) {
        ApplicationDocument appDoc = applicationDocumentRepository.findByApplicationIdAndDocumentId(applicationId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Application document mapping not found for doc: " + documentId));

        Employee verifier = employeeRepository.findByUserId(actor.getId()).orElse(null);

        appDoc.setVerificationStatus(request.status());
        appDoc.setVerificationComment(request.comment());
        appDoc.setVerifiedBy(verifier);
        appDoc.setVerifiedAt(Instant.now());

        appDoc = applicationDocumentRepository.save(appDoc);
        log.info("Application document {} for app {} verified with status {}", documentId, applicationId, request.status());

        // Phase A10: notify the candidate (consumed AFTER_COMMIT).
        eventPublisher.publishEvent(new DocumentVerifiedEvent(
                applicationId,
                documentId,
                appDoc.getDocument().getType().name(),
                request.status().name(),
                appDoc.getApplication().getCandidate().getUser().getId(),
                actor.getId()));

        DocumentVersion lv = documentVersionRepository.findTopByDocumentIdOrderByVersionNumberDesc(appDoc.getDocument().getId()).orElse(null);
        FileAsset fa = lv != null ? lv.getFile() : null;
        return ApplicationDocumentResponse.from(appDoc, DocumentResponse.from(appDoc.getDocument(), lv, fa));
    }

    // -------------------------------------------------------------------------
    // Internship Documents
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InternshipDocumentResponse> getDocumentsForInternship(UUID internshipId, UserPrincipal actor) {
        Internship internship = internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));

        if (actor.hasRole("CANDIDATE") && !internship.getCandidate().getUser().getId().equals(actor.getId())) {
            throw new ResourceNotFoundException("Internship not found or you do not have permission to view it.");
        }

        return internshipDocumentRepository.findByInternshipId(internshipId).stream()
                .map(idDoc -> {
                    DocumentVersion lv = documentVersionRepository.findTopByDocumentIdOrderByVersionNumberDesc(idDoc.getDocument().getId()).orElse(null);
                    FileAsset fa = lv != null ? lv.getFile() : null;
                    return InternshipDocumentResponse.from(idDoc, DocumentResponse.from(idDoc.getDocument(), lv, fa));
                })
                .toList();
    }

    @Transactional
    public InternshipDocumentResponse attachDocumentToInternship(UUID internshipId, UUID documentId, Boolean mandatory, UserPrincipal actor) {
        Internship internship = internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));

        InternshipDocument idDoc = internshipDocumentRepository.findByInternshipIdAndDocumentId(internshipId, documentId)
                .orElseGet(() -> new InternshipDocument(internship, document, mandatory != null ? mandatory : true));

        idDoc.setMandatory(mandatory != null ? mandatory : true);
        idDoc = internshipDocumentRepository.save(idDoc);

        DocumentVersion lv = documentVersionRepository.findTopByDocumentIdOrderByVersionNumberDesc(document.getId()).orElse(null);
        FileAsset fa = lv != null ? lv.getFile() : null;
        return InternshipDocumentResponse.from(idDoc, DocumentResponse.from(document, lv, fa));
    }

    private String generateDocumentReference() {
        int year = Year.now().getValue();
        String prefix = "DOC-" + year + "-";
        long count = documentRepository.countByReferencePrefix(prefix);
        return String.format("%s%05d", prefix, count + 1);
    }
}
