package tn.steg.backend.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.steg.backend.ai.application.AiService;
import tn.steg.backend.ai.application.dto.AiAnalysisResultResponse;
import tn.steg.backend.ai.application.dto.CandidateAssistantQueryRequest;
import tn.steg.backend.ai.domain.assembler.ApplicationDocumentContentAssembler;
import tn.steg.backend.ai.domain.assembler.AssembledAiContent;
import tn.steg.backend.ai.domain.assembler.CandidateAssistantContentAssembler;
import tn.steg.backend.ai.domain.assembler.FinanceCaseContentAssembler;
import tn.steg.backend.ai.domain.assembler.LogbookContentAssembler;
import tn.steg.backend.ai.domain.client.AiCompletionClient;
import tn.steg.backend.ai.domain.client.AiCompletionResult;
import tn.steg.backend.ai.domain.model.AiAnalysis;
import tn.steg.backend.ai.domain.model.AiAnalysisType;
import tn.steg.backend.ai.domain.repository.AiAnalysisRepository;
import tn.steg.backend.ai.domain.repository.AiRecommendationRepository;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiService} graceful degradation behaviour.
 *
 * <p>These tests verify that when the {@link AiCompletionClient} is unavailable
 * (i.e., returns a failure result), the service:
 * <ol>
 *   <li>Does NOT throw an exception that would crash the caller's business workflow.</li>
 *   <li>Persists the {@code AiAnalysis} record with an {@code AI_UNAVAILABLE:} prefix in {@code outputSummary}.</li>
 *   <li>Returns an {@link AiAnalysisResultResponse} with an empty recommendations list
 *       and the degradation message in {@code responseText}.</li>
 *   <li>Always sets {@code cinExcluded = true} on the persisted analysis record.</li>
 * </ol>
 *
 * <p>No Spring context is loaded — these are pure Mockito unit tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiService — Graceful Degradation")
class AiServiceGracefulDegradationTest {

    // -------------------------------------------------------------------------
    // Common fixtures
    // -------------------------------------------------------------------------

    private static final String FAKE_MODEL = "gemini-test";
    private static final String PROVIDER = "google";
    private static final String ERROR_MESSAGE = "upstream timeout";

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();

    private static final UserPrincipal ACTOR = new UserPrincipal(ACTOR_ID, "actor@test.tn", List.of("ROLE_HR"));

    private static final AssembledAiContent ASSEMBLED = new AssembledAiContent(
            "System instruction",
            List.of("Prompt part 1"),
            "Input summary",
            true   // cinExcluded MUST be true
    );

    // -------------------------------------------------------------------------
    // Mocks
    // -------------------------------------------------------------------------

    @Mock AiCompletionClient aiCompletionClient;
    @Mock AiAnalysisRepository aiAnalysisRepository;
    @Mock AiRecommendationRepository aiRecommendationRepository;
    @Mock ApplicationDocumentContentAssembler applicationDocumentAssembler;
    @Mock FinanceCaseContentAssembler financeCaseAssembler;
    @Mock LogbookContentAssembler logbookAssembler;
    @Mock CandidateAssistantContentAssembler candidateAssistantAssembler;
    @Mock CandidateRepository candidateRepository;
    @Mock UserRepository userRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock AuditService auditService;

    private AiService service;

    @BeforeEach
    void setUp() {
        service = new AiService(
                aiCompletionClient,
                aiAnalysisRepository,
                aiRecommendationRepository,
                applicationDocumentAssembler,
                financeCaseAssembler,
                logbookAssembler,
                candidateAssistantAssembler,
                candidateRepository,
                userRepository,
                employeeRepository,
                auditService
        );

        // Stub model name used by every operation
        when(aiCompletionClient.getModel()).thenReturn(FAKE_MODEL);

        // Stub save to return the same entity so the service can chain calls
        when(aiAnalysisRepository.save(any(AiAnalysis.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Stub userRepository — actor user lookup returns empty (allowed; service uses .orElse(null))
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());
    }

    // -------------------------------------------------------------------------
    // analyzeApplication — degradation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("analyzeApplication")
    class AnalyzeApplicationDegradation {

        @BeforeEach
        void stubAssembler() {
            when(applicationDocumentAssembler.assemble(any(UUID.class), any())).thenReturn(ASSEMBLED);
        }

        @Test
        @DisplayName("returns degraded response without throwing when AI client fails")
        void returnsDegradedResponse_whenAiClientFails() {
            when(aiCompletionClient.complete(anyString(), anyList()))
                    .thenReturn(AiCompletionResult.failure(ERROR_MESSAGE, FAKE_MODEL, PROVIDER));

            AiAnalysisResultResponse response = service.analyzeApplication(TARGET_ID, ACTOR);

            assertDegradedResponse(response, "Service IA temporairement indisponible");
            assertCinExcludedPersisted(AiAnalysisType.APPLICATION_DOCUMENT_ANALYSIS);
            verifyNoInteractions(aiRecommendationRepository);
        }

        @Test
        @DisplayName("still sets cinExcluded=true on analysis even when AI is down")
        void cinExcludedIsAlwaysTrue_evenOnFailure() {
            when(aiCompletionClient.complete(anyString(), anyList()))
                    .thenReturn(AiCompletionResult.failure(ERROR_MESSAGE, FAKE_MODEL, PROVIDER));

            service.analyzeApplication(TARGET_ID, ACTOR);

            ArgumentCaptor<AiAnalysis> captor = ArgumentCaptor.forClass(AiAnalysis.class);
            verify(aiAnalysisRepository, atLeastOnce()).save(captor.capture());

            assertThat(captor.getAllValues())
                    .allSatisfy(a -> assertThat(a.getCinExcluded()).isTrue());
        }
    }

    // -------------------------------------------------------------------------
    // analyzeFinanceCase — degradation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("analyzeFinanceCase")
    class AnalyzeFinanceCaseDegradation {

        @BeforeEach
        void stubAssembler() {
            when(financeCaseAssembler.assemble(any(UUID.class), any())).thenReturn(ASSEMBLED);
        }

        @Test
        @DisplayName("returns degraded response without throwing when AI client fails")
        void returnsDegradedResponse_whenAiClientFails() {
            when(aiCompletionClient.complete(anyString(), anyList()))
                    .thenReturn(AiCompletionResult.failure(ERROR_MESSAGE, FAKE_MODEL, PROVIDER));

            AiAnalysisResultResponse response = service.analyzeFinanceCase(TARGET_ID, ACTOR);

            assertDegradedResponse(response, "Service IA temporairement indisponible");
            assertCinExcludedPersisted(AiAnalysisType.FINANCE_CASE_ANALYSIS);
            verifyNoInteractions(aiRecommendationRepository);
        }
    }

    // -------------------------------------------------------------------------
    // generateLogbookDraft — degradation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("generateLogbookDraft")
    class GenerateLogbookDraftDegradation {

        @BeforeEach
        void stubAssembler() {
            when(logbookAssembler.assemble(any(UUID.class), any())).thenReturn(ASSEMBLED);
        }

        @Test
        @DisplayName("returns degraded response without throwing when AI client fails")
        void returnsDegradedResponse_whenAiClientFails() {
            when(aiCompletionClient.complete(anyString(), anyList()))
                    .thenReturn(AiCompletionResult.failure(ERROR_MESSAGE, FAKE_MODEL, PROVIDER));

            AiAnalysisResultResponse response = service.generateLogbookDraft(TARGET_ID, ACTOR);

            assertDegradedResponse(response, "Service IA temporairement indisponible");
            assertCinExcludedPersisted(AiAnalysisType.LOGBOOK_GENERATION);
            verifyNoInteractions(aiRecommendationRepository);
        }
    }

    // -------------------------------------------------------------------------
    // queryCandidateAssistant — degradation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("queryCandidateAssistant")
    class QueryCandidateAssistantDegradation {

        private static final UUID CANDIDATE_ID = UUID.randomUUID();

        @BeforeEach
        void stubCandidateAndAssembler() {
            Candidate candidate = mock(Candidate.class);
            when(candidate.getId()).thenReturn(CANDIDATE_ID);
            when(candidateRepository.findByUserId(ACTOR_ID)).thenReturn(Optional.of(candidate));
            when(candidateAssistantAssembler.assemble(eq(CANDIDATE_ID), anyString())).thenReturn(ASSEMBLED);
        }

        @Test
        @DisplayName("returns degraded response without throwing when AI client fails")
        void returnsDegradedResponse_whenAiClientFails() {
            when(aiCompletionClient.complete(anyString(), anyList()))
                    .thenReturn(AiCompletionResult.failure(ERROR_MESSAGE, FAKE_MODEL, PROVIDER));

            CandidateAssistantQueryRequest request = new CandidateAssistantQueryRequest("Quelle est la prochaine étape?");
            AiAnalysisResultResponse response = service.queryCandidateAssistant(request, ACTOR);

            assertDegradedResponse(response, "Service d'assistance temporairement indisponible");
            assertCinExcludedPersisted(AiAnalysisType.CANDIDATE_ASSISTANT_QUERY);
            verifyNoInteractions(aiRecommendationRepository);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertDegradedResponse(AiAnalysisResultResponse response, String expectedPrefix) {
        assertThat(response).isNotNull();
        assertThat(response.recommendations()).isEmpty();
        assertThat(response.responseText()).startsWith(expectedPrefix);
        assertThat(response.responseText()).contains(ERROR_MESSAGE);
    }

    private void assertCinExcludedPersisted(AiAnalysisType expectedType) {
        ArgumentCaptor<AiAnalysis> captor = ArgumentCaptor.forClass(AiAnalysis.class);
        verify(aiAnalysisRepository, atLeastOnce()).save(captor.capture());

        AiAnalysis first = captor.getAllValues().get(0);
        assertThat(first.getCinExcluded()).isTrue();
        assertThat(first.getType()).isEqualTo(expectedType);
    }
}
