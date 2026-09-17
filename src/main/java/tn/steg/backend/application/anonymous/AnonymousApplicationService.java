package tn.steg.backend.application.anonymous;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.application.application.dto.AnonymousApplicationSubmitRequest;
import tn.steg.backend.application.application.dto.AnonymousSubmissionResponse;
import tn.steg.backend.application.application.dto.ApplicationCreateRequest;
import tn.steg.backend.application.application.dto.ApplicationResponse;
import tn.steg.backend.application.application.ApplicationService;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.candidate.domain.repository.UniversityRepository;
import tn.steg.backend.candidate.domain.service.NationalIdHasher;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.application.DocumentService;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.workflow.application.WorkflowService;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnonymousApplicationService {

    private static final String CANDIDATE_ROLE_CODE = "CANDIDATE";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CandidateRepository candidateRepository;
    private final UniversityRepository universityRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final InternshipApplicationRepository applicationRepository;
    private final ApplicationService applicationService;
    private final DocumentService documentService;
    private final WorkflowService workflowService;
    private final AuditService auditService;

    @Transactional
    public AnonymousSubmissionResponse submit(AnonymousApplicationSubmitRequest request,
                                             List<MultipartFile> files) {
        String nationalIdHash = NationalIdHasher.sha256Hex(request.nationalId());

        if (candidateRepository.existsByNationalIdHash(nationalIdHash)) {
            Candidate existing = candidateRepository.findByNationalIdHash(nationalIdHash)
                    .orElseThrow(() -> new ResourceNotFoundException("Candidate not found"));
            List<InternshipApplication> apps = applicationRepository.findByCandidateId(existing.getId());
            if (!apps.isEmpty()) {
                throw new BusinessRuleException("APPLICATION_ALREADY_EXISTS_FOR_CIN",
                        "A candidate with this national ID already has a submitted application.");
            }
        }

        User user = resolveOrCreateUser(request, nationalIdHash);
        Candidate candidate = resolveOrCreateCandidate(request, nationalIdHash, user);

        ApplicationCreateRequest createReq = new ApplicationCreateRequest(
                request.desiredStartDate(),
                request.desiredEndDate(),
                true
        );
        UserPrincipal principal = new UserPrincipal(
                user.getId(),
                user.getEmail(),
                List.of("ROLE_" + CANDIDATE_ROLE_CODE)
        );
        ApplicationResponse appResponse = applicationService.createApplication(createReq, principal);
        UUID applicationId = appResponse.id();

        attachDocuments(applicationId, files, principal);

        ApplicationResponse submitted = applicationService.submitApplication(applicationId, principal);

        String trackingToken = generateTrackingToken();
        String trackingTokenHash = NationalIdHasher.sha256Hex(trackingToken);

        InternshipApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        app.setTrackingTokenHash(trackingTokenHash);
        applicationRepository.save(app);

        log.info("Anonymous application submitted: ref={} candidate={}", submitted.reference(), candidate.getId());
        // E1.5: anonymous intake creates identity + credentials + tracking token — audit
        // the fact with field-level values only (never CIN, password, or token material).
        auditService.log("ANONYMOUS_APPLICATION_SUBMITTED", "InternshipApplication", applicationId, null,
                java.util.Map.of("reference", submitted.reference(), "candidateId", candidate.getId().toString()),
                user.getId(), null, CANDIDATE_ROLE_CODE, null);

        return new AnonymousSubmissionResponse(
                submitted.reference(),
                submitted.status().name(),
                trackingToken
        );
    }

    private User resolveOrCreateUser(AnonymousApplicationSubmitRequest request, String nationalIdHash) {
        return userRepository.findByEmail(request.email())
                .map(user -> {
                    log.info("Reusing existing user for anonymous submission: {}", user.getEmail());
                    return user;
                })
                .orElseGet(() -> {
                    String randomPassword = generateRandomPassword();
                    Role candidateRole = roleRepository.findByCode(CANDIDATE_ROLE_CODE)
                            .orElseThrow(() -> new IllegalStateException("CANDIDATE role not found"));
                    User user = new User(request.email(), passwordEncoder.encode(randomPassword), UserStatus.ACTIVE);
                    user.setEnabled(true);
                    user.setAssignedRoles(java.util.Set.of(candidateRole));
                    return userRepository.save(user);
                });
    }

    private Candidate resolveOrCreateCandidate(AnonymousApplicationSubmitRequest request,
                                               String nationalIdHash, User user) {
        return candidateRepository.findByNationalIdHash(nationalIdHash)
                .map(candidate -> {
                    candidate.setFirstName(request.firstName());
                    candidate.setLastName(request.lastName());
                    candidate.setEmail(request.email());
                    candidate.setPhone(request.phone());
                    candidate.setBirthDate(request.birthDate());
                    candidate.setNationalIdEncrypted(request.nationalId());
                    return candidateRepository.save(candidate);
                })
                .orElseGet(() -> {
                    // E3: university is mandatory (NOT NULL) — resolve from the request.
                    University university = universityRepository.findById(request.universityId())
                            .orElseThrow(() -> new ResourceNotFoundException(
                                    "University not found: " + request.universityId()));
                    Candidate candidate = new Candidate(
                            request.firstName(),
                            request.lastName(),
                            request.email(),
                            nationalIdHash,
                            university
                    );
                    candidate.setNationalIdEncrypted(request.nationalId());
                    candidate.setPhone(request.phone());
                    candidate.setBirthDate(request.birthDate());
                    candidate.setSpeciality(request.speciality());
                    candidate.setDiploma(request.diploma());
                    candidate.setUser(user);
                    return candidateRepository.save(candidate);
                });
    }

    private void attachDocuments(UUID applicationId, List<MultipartFile> files, UserPrincipal principal) {
        if (files == null || files.isEmpty()) {
            return;
        }
        for (MultipartFile file : files) {
            DocumentType type = resolveDocumentType(file.getOriginalFilename());
            if (type == null) {
                continue;
            }
            try {
                // E3: upload then LINK to the application, otherwise HR review,
                // dossier completeness and tracking summaries never see the file.
                var uploaded = documentService.uploadDocument(file, type, principal);
                documentService.attachDocumentToApplication(applicationId, uploaded.id(), true, principal);
            } catch (Exception e) {
                log.warn("Document upload skipped for {}: {}", file.getOriginalFilename(), e.getMessage());
            }
        }
    }

    private DocumentType resolveDocumentType(String filename) {
        if (filename == null) {
            return null;
        }
        String lower = filename.toLowerCase();
        if (lower.contains("demande") || lower.contains("application") || lower.contains("intership")
                || lower.contains("internship") || lower.contains("convention")) {
            return DocumentType.INTERNSHIP_APPLICATION;
        }
        if (lower.contains("assignment") || lower.contains("lettre") || lower.contains("affectation")) {
            return DocumentType.ASSIGNMENT_LETTER;
        }
        return null;
    }

    private String generateTrackingToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return NationalIdHasher.sha256Hex(Base64.getEncoder().encodeToString(bytes));
    }

    private String generateRandomPassword() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
