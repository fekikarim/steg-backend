package tn.steg.backend.ai.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.ai.application.dto.*;
import tn.steg.backend.ai.domain.assembler.ApplicationDocumentContentAssembler;
import tn.steg.backend.ai.domain.assembler.AssembledAiContent;
import tn.steg.backend.ai.domain.assembler.CandidateAssistantContentAssembler;
import tn.steg.backend.ai.domain.assembler.FinanceCaseContentAssembler;
import tn.steg.backend.ai.domain.assembler.LogbookContentAssembler;
import tn.steg.backend.ai.domain.client.AiCompletionClient;
import tn.steg.backend.ai.domain.client.AiCompletionResult;
import tn.steg.backend.ai.domain.model.AiAnalysis;
import tn.steg.backend.ai.domain.model.AiAnalysisType;
import tn.steg.backend.ai.domain.model.AiRecommendation;
import tn.steg.backend.ai.domain.model.AiRecommendationStatus;
import tn.steg.backend.ai.domain.repository.AiAnalysisRepository;
import tn.steg.backend.ai.domain.repository.AiRecommendationRepository;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Application service for the AI assistance layer.
 *
 * <p>Strict Advisory Architectural Rules:
 * <ul>
 *   <li>AI is strictly advisory — it CANNOT directly mutate application, internship, finance, or evaluation state.
 *   <li>Only creates {@link AiRecommendation} in {@code PROPOSED} status.
 *   <li>Human action (Employee) decides whether to accept or dismiss via {@link #reviewRecommendation}.
 *   <li>CIN and restricted documents are structurally excluded at the repository query level.
 *   <li>Graceful degradation: if the AI client fails, the caller receives a controlled response without crashing the business workflow.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final AiCompletionClient aiCompletionClient;
    private final AiAnalysisRepository aiAnalysisRepository;
    private final AiRecommendationRepository aiRecommendationRepository;
    private final ApplicationDocumentContentAssembler applicationDocumentAssembler;
    private final FinanceCaseContentAssembler financeCaseAssembler;
    private final LogbookContentAssembler logbookAssembler;
    private final CandidateAssistantContentAssembler candidateAssistantAssembler;
    private final CandidateRepository candidateRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    // -------------------------------------------------------------------------
    // 1. Application Document Analysis
    // -------------------------------------------------------------------------

    @Transactional
    public AiAnalysisResultResponse analyzeApplication(UUID applicationId, UserPrincipal actor) {
        log.info("Analyzing application documents for appId={} requestedBy={}", applicationId, actor.getId());

        // 1. Assemble content (CIN/restricted documents excluded structurally at query level)
        AssembledAiContent assembled = applicationDocumentAssembler.assemble(applicationId, null);

        // 2. Persist AiAnalysis record with cinExcluded = true unconditionally
        User requestedBy = userRepository.findById(actor.getId()).orElse(null);
        AiAnalysis analysis = new AiAnalysis(
                AiAnalysisType.APPLICATION_DOCUMENT_ANALYSIS,
                "InternshipApplication",
                applicationId,
                aiCompletionClient.getModel()
        );
        analysis.setCinExcluded(true);
        analysis.setInputSummary(assembled.inputSummary());
        analysis.setRequestedBy(requestedBy);
        analysis = aiAnalysisRepository.save(analysis);

        // 3. Invoke AI model with graceful degradation
        AiCompletionResult result = aiCompletionClient.complete(assembled.systemInstruction(), assembled.promptParts());

        if (!result.success()) {
            analysis.setOutputSummary("AI_UNAVAILABLE: " + result.errorMessage());
            aiAnalysisRepository.save(analysis);
            return new AiAnalysisResultResponse(
                    AiAnalysisResponse.from(analysis),
                    Collections.emptyList(),
                    "Service IA temporairement indisponible: " + result.errorMessage()
            );
        }

        // 4. Record output & advisory recommendation (PROPOSED)
        analysis.setOutputSummary(result.content());
        aiAnalysisRepository.save(analysis);

        AiRecommendation recommendation = new AiRecommendation(analysis, result.content());
        recommendation = aiRecommendationRepository.save(recommendation);

        auditService.log("AI_APPLICATION_ANALYZED", "AiAnalysis", analysis.getId(), null, null, actor.getId(), null);

        return new AiAnalysisResultResponse(
                AiAnalysisResponse.from(analysis),
                List.of(AiRecommendationResponse.from(recommendation)),
                result.content()
        );
    }

    // -------------------------------------------------------------------------
    // 2. Finance Case Analysis
    // -------------------------------------------------------------------------

    @Transactional
    public AiAnalysisResultResponse analyzeFinanceCase(UUID financeCaseId, UserPrincipal actor) {
        log.info("Analyzing finance case id={} requestedBy={}", financeCaseId, actor.getId());

        // 1. Assemble content (CIN excluded structurally at query level)
        AssembledAiContent assembled = financeCaseAssembler.assemble(financeCaseId, null);

        // 2. Persist AiAnalysis record with cinExcluded = true unconditionally
        User requestedBy = userRepository.findById(actor.getId()).orElse(null);
        AiAnalysis analysis = new AiAnalysis(
                AiAnalysisType.FINANCE_CASE_ANALYSIS,
                "FinanceCase",
                financeCaseId,
                aiCompletionClient.getModel()
        );
        analysis.setCinExcluded(true);
        analysis.setInputSummary(assembled.inputSummary());
        analysis.setRequestedBy(requestedBy);
        analysis = aiAnalysisRepository.save(analysis);

        // 3. Invoke AI model with graceful degradation
        AiCompletionResult result = aiCompletionClient.complete(assembled.systemInstruction(), assembled.promptParts());

        if (!result.success()) {
            analysis.setOutputSummary("AI_UNAVAILABLE: " + result.errorMessage());
            aiAnalysisRepository.save(analysis);
            return new AiAnalysisResultResponse(
                    AiAnalysisResponse.from(analysis),
                    Collections.emptyList(),
                    "Service IA temporairement indisponible: " + result.errorMessage()
            );
        }

        // 4. Record output & advisory recommendation (PROPOSED)
        analysis.setOutputSummary(result.content());
        aiAnalysisRepository.save(analysis);

        AiRecommendation recommendation = new AiRecommendation(analysis, result.content());
        recommendation = aiRecommendationRepository.save(recommendation);

        auditService.log("AI_FINANCE_CASE_ANALYZED", "AiAnalysis", analysis.getId(), null, null, actor.getId(), null);

        return new AiAnalysisResultResponse(
                AiAnalysisResponse.from(analysis),
                List.of(AiRecommendationResponse.from(recommendation)),
                result.content()
        );
    }

    // -------------------------------------------------------------------------
    // 3. Logbook Generation
    // -------------------------------------------------------------------------

    @Transactional
    public AiAnalysisResultResponse generateLogbookDraft(UUID internshipId, UserPrincipal actor) {
        log.info("Generating logbook draft for internship id={} requestedBy={}", internshipId, actor.getId());

        // 1. Assemble content based on recorded journal entries, tasks, deliverables
        AssembledAiContent assembled = logbookAssembler.assemble(internshipId, null);

        // 2. Persist AiAnalysis record
        User requestedBy = userRepository.findById(actor.getId()).orElse(null);
        AiAnalysis analysis = new AiAnalysis(
                AiAnalysisType.LOGBOOK_GENERATION,
                "Internship",
                internshipId,
                aiCompletionClient.getModel()
        );
        analysis.setCinExcluded(true);
        analysis.setInputSummary(assembled.inputSummary());
        analysis.setRequestedBy(requestedBy);
        analysis = aiAnalysisRepository.save(analysis);

        // 3. Invoke AI model
        AiCompletionResult result = aiCompletionClient.complete(assembled.systemInstruction(), assembled.promptParts());

        if (!result.success()) {
            analysis.setOutputSummary("AI_UNAVAILABLE: " + result.errorMessage());
            aiAnalysisRepository.save(analysis);
            return new AiAnalysisResultResponse(
                    AiAnalysisResponse.from(analysis),
                    Collections.emptyList(),
                    "Service IA temporairement indisponible: " + result.errorMessage()
            );
        }

        // 4. Record output & advisory recommendation (PROPOSED draft)
        analysis.setOutputSummary(result.content());
        aiAnalysisRepository.save(analysis);

        AiRecommendation recommendation = new AiRecommendation(analysis, result.content());
        recommendation = aiRecommendationRepository.save(recommendation);

        auditService.log("AI_LOGBOOK_GENERATED", "AiAnalysis", analysis.getId(), null, null, actor.getId(), null);

        return new AiAnalysisResultResponse(
                AiAnalysisResponse.from(analysis),
                List.of(AiRecommendationResponse.from(recommendation)),
                result.content()
        );
    }

    // -------------------------------------------------------------------------
    // 4. Candidate Assistant Q&A
    // -------------------------------------------------------------------------

    @Transactional
    public AiAnalysisResultResponse queryCandidateAssistant(CandidateAssistantQueryRequest request, UserPrincipal actor) {
        Candidate candidate = candidateRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate profile not found for user: " + actor.getId()));

        AssembledAiContent assembled = candidateAssistantAssembler.assemble(candidate.getId(), request.question());

        User requestedBy = userRepository.findById(actor.getId()).orElse(null);
        AiAnalysis analysis = new AiAnalysis(
                AiAnalysisType.CANDIDATE_ASSISTANT_QUERY,
                "Candidate",
                candidate.getId(),
                aiCompletionClient.getModel()
        );
        analysis.setCinExcluded(true);
        analysis.setInputSummary(assembled.inputSummary());
        analysis.setRequestedBy(requestedBy);
        analysis = aiAnalysisRepository.save(analysis);

        AiCompletionResult result = aiCompletionClient.complete(assembled.systemInstruction(), assembled.promptParts());

        if (!result.success()) {
            analysis.setOutputSummary("AI_UNAVAILABLE: " + result.errorMessage());
            aiAnalysisRepository.save(analysis);
            return new AiAnalysisResultResponse(
                    AiAnalysisResponse.from(analysis),
                    Collections.emptyList(),
                    "Service d'assistance temporairement indisponible: " + result.errorMessage()
            );
        }

        analysis.setOutputSummary(result.content());
        aiAnalysisRepository.save(analysis);

        return new AiAnalysisResultResponse(
                AiAnalysisResponse.from(analysis),
                Collections.emptyList(),
                result.content()
        );
    }

    // -------------------------------------------------------------------------
    // 5. Review Recommendation (Traceability linking)
    // -------------------------------------------------------------------------

    @Transactional
    public AiRecommendationResponse reviewRecommendation(UUID recommendationId,
                                                         AiRecommendationReviewRequest request,
                                                         UserPrincipal actor) {
        AiRecommendation recommendation = aiRecommendationRepository.findById(recommendationId)
                .orElseThrow(() -> new ResourceNotFoundException("Recommendation not found: " + recommendationId));

        if (request.status() == AiRecommendationStatus.PROPOSED) {
            throw new BusinessRuleException("INVALID_STATUS", "Recommendation status can only be transitioned to ACCEPTED_BY_HUMAN or DISMISSED.");
        }

        Employee reviewer = employeeRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee profile not found for user: " + actor.getId()));

        recommendation.setStatus(request.status());
        recommendation.setReviewedBy(reviewer);
        recommendation.setReviewedAt(Instant.now());
        recommendation = aiRecommendationRepository.save(recommendation);

        auditService.log("AI_RECOMMENDATION_REVIEWED", "AiRecommendation", recommendationId, null,
                request.status().name(), actor.getId(), null);

        log.info("AI Recommendation {} reviewed as {} by reviewer {}",
                recommendationId, request.status(), reviewer.getId());

        return AiRecommendationResponse.from(recommendation);
    }
}
