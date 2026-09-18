package tn.steg.backend.application.interfaces.rest.anonymous;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.application.anonymous.AnonymousApplicationService;
import tn.steg.backend.application.application.dto.AnonymousApplicationSubmitRequest;
import tn.steg.backend.application.application.dto.AnonymousSubmissionResponse;
import tn.steg.backend.application.application.dto.PublicApplicationTrackRequest;
import tn.steg.backend.application.application.dto.PublicApplicationTrackingResponse;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.candidate.domain.service.NationalIdHasher;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.interfaces.rest.PublicEndpoint;
import tn.steg.backend.document.domain.repository.ApplicationDocumentRepository;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/public/applications")
@RequiredArgsConstructor
@Tag(name = "Anonymous Applications", description = "Public endpoints for anonymous internship application submission and tracking")
public class AnonymousApplicationController {

    private final AnonymousApplicationService anonymousApplicationService;
    private final InternshipApplicationRepository applicationRepository;
    private final CandidateRepository candidateRepository;
    private final ApplicationDocumentRepository applicationDocumentRepository;

    @PublicEndpoint
    @PostMapping(consumes = {"multipart/form-data", "application/json"})
    @Operation(summary = "Submit an internship application without an account")
    public ResponseEntity<AnonymousSubmissionResponse> submit(
            @Valid @RequestPart("application") AnonymousApplicationSubmitRequest request,
            @RequestPart(value = "documents", required = false) List<MultipartFile> documents) {
        AnonymousSubmissionResponse response = anonymousApplicationService.submit(request, documents);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PublicEndpoint
    @PostMapping("/track")
    @Operation(summary = "Track an application without an account")
    @Transactional(readOnly = true)
    public ResponseEntity<PublicApplicationTrackingResponse> track(
            @Valid @RequestBody PublicApplicationTrackRequest request) {
        String hash = NationalIdHasher.sha256Hex(request.trackingToken());
        var appOpt = applicationRepository.findByTrackingTokenHash(hash);
        if (appOpt.isEmpty()) {
            throw new ResourceNotFoundException("Application not found.");
        }
        var app = appOpt.get();
        Candidate cand = app.getCandidate();
        String calculatedType = app.getCalculatedType() != null ? app.getCalculatedType().name() : null;
        String requirement = app.getRequirement() != null ? app.getRequirement().name() : null;

        String explanation = switch (app.getStatus()) {
            case DRAFT -> "Your draft is saved. Review and submit when ready.";
            case SUBMITTED -> "Your application has been submitted and is under review.";
            case UNDER_REVIEW -> "Your application is being reviewed by our team.";
            case NEEDS_CORRECTION -> "Additional information is required. Please check your documents.";
            case ACCEPTED -> "Congratulations! Your application has been accepted.";
            case REJECTED -> "We are sorry, your application was not accepted this cycle.";
            case WITHDRAWN -> "Your application has been withdrawn.";
        };

        String nextStep = switch (app.getStatus()) {
            case DRAFT -> "Continue filling in your application and submit.";
            case SUBMITTED -> "Wait for the review team to evaluate your dossier.";
            case UNDER_REVIEW -> "The review is in progress. Check notifications for updates.";
            case NEEDS_CORRECTION -> "Upload the requested documents or provide clarifications.";
            case ACCEPTED -> "Prepare for your internship onboarding.";
            case REJECTED -> "You may reapply in the next cycle.";
            case WITHDRAWN -> "No further action required.";
        };

        var timeline = new java.util.ArrayList<String>();
        timeline.add("Created: " + app.getCreatedAt());
        if (app.getSubmissionDate() != null) {
            timeline.add("Submitted: " + app.getSubmissionDate());
        }
        if (app.getStatus() == ApplicationStatus.UNDER_REVIEW ||
            app.getStatus() == ApplicationStatus.NEEDS_CORRECTION ||
            app.getStatus() == ApplicationStatus.ACCEPTED ||
            app.getStatus() == ApplicationStatus.REJECTED) {
            timeline.add("Under Review: " + (app.getUpdatedAt() != null ? app.getUpdatedAt() : app.getSubmissionDate()));
        }
        if (app.getStatus() == ApplicationStatus.NEEDS_CORRECTION) {
            timeline.add("Additional Information Requested: " + app.getUpdatedAt());
        } else if (app.getStatus() == ApplicationStatus.ACCEPTED) {
            timeline.add("Accepted: " + app.getUpdatedAt());
        } else if (app.getStatus() == ApplicationStatus.REJECTED) {
            timeline.add("Decision Recorded: " + app.getUpdatedAt());
        } else if (app.getStatus() == ApplicationStatus.WITHDRAWN) {
            timeline.add("Withdrawn: " + app.getUpdatedAt());
        }

        // E3: real document summary from linked application documents
        // (type + verification status only — no file names, no content).
        List<String> docsSummary = applicationDocumentRepository.findByApplicationId(app.getId()).stream()
                .map(link -> link.getDocument().getType().name() + ": "
                        + link.getVerificationStatus().name())
                .sorted()
                .toList();

        return ResponseEntity.ok(new PublicApplicationTrackingResponse(
                app.getReference(),
                app.getStatus(),
                calculatedType,
                requirement,
                explanation,
                nextStep,
                timeline,
                docsSummary
        ));
    }
}
