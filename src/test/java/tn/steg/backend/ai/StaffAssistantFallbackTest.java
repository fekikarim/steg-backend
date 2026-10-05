package tn.steg.backend.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.steg.backend.ai.application.AiService;
import tn.steg.backend.ai.application.dto.AiAnalysisResultResponse;
import tn.steg.backend.ai.application.dto.CandidateAssistantQueryRequest;
import tn.steg.backend.ai.domain.assembler.AssembledAiContent;
import tn.steg.backend.ai.domain.assembler.StaffAssistantContentAssembler;
import tn.steg.backend.ai.domain.client.AiCompletionClient;
import tn.steg.backend.ai.domain.client.AiCompletionResult;
import tn.steg.backend.ai.domain.model.AiAnalysis;
import tn.steg.backend.ai.domain.model.AiAnalysisType;
import tn.steg.backend.ai.domain.repository.AiAnalysisRepository;
import tn.steg.backend.ai.domain.repository.AiRecommendationRepository;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;
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
 * Unit tests for the STAFF assistant graceful degradation: when Gemini is
 * unavailable, {@code queryStaffAssistant} must answer from the deterministic
 * fallback (assembled backend aggregates) instead of returning an error —
 * the back-office chatbot stays useful and every figure remains verifiable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiService — Staff assistant rule-based fallback")
class StaffAssistantFallbackTest {

    private static final String FAKE_MODEL = "gemini-test";
    private static final String PROVIDER = "google";
    private static final String ERROR_MESSAGE = "AI service is not configured with an API key.";

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UserPrincipal ACTOR =
            new UserPrincipal(ACTOR_ID, "admin.steg@steg.tn", List.of("ROLE_ADMIN"));

    private static final AssembledAiContent ASSEMBLED_WITH_FALLBACK = new AssembledAiContent(
            "System instruction",
            List.of("Prompt part 1"),
            "Input summary",
            true,
            "Synthèse de la plateforme : candidatures en attente = 3 ; stages actifs = 1."
    );

    @Mock AiCompletionClient aiCompletionClient;
    @Mock AiAnalysisRepository aiAnalysisRepository;
    @Mock AiRecommendationRepository aiRecommendationRepository;
    @Mock tn.steg.backend.ai.domain.assembler.ApplicationDocumentContentAssembler applicationDocumentAssembler;
    @Mock tn.steg.backend.ai.domain.assembler.FinanceCaseContentAssembler financeCaseAssembler;
    @Mock tn.steg.backend.ai.domain.assembler.LogbookContentAssembler logbookAssembler;
    @Mock tn.steg.backend.ai.domain.assembler.CandidateAssistantContentAssembler candidateAssistantAssembler;
    @Mock tn.steg.backend.ai.domain.knowledge.StegKnowledgeBase knowledgeBase;
    @Mock tn.steg.backend.ai.domain.assembler.InternAssistantContentAssembler internAssistantAssembler;
    @Mock StaffAssistantContentAssembler staffAssistantAssembler;
    @Mock tn.steg.backend.ai.domain.service.LogbookFidelityGate fidelityChecker;
    @Mock tn.steg.backend.companion.domain.repository.InternshipJournalRepository journalRepository;
    @Mock tn.steg.backend.companion.domain.repository.JournalEntryRepository journalEntryRepository;
    @Mock tn.steg.backend.companion.domain.repository.TaskRepository taskRepository;
    @Mock tn.steg.backend.companion.domain.repository.DeliverableRepository deliverableRepository;
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
                internAssistantAssembler,
                staffAssistantAssembler,
                knowledgeBase,
                fidelityChecker,
                journalRepository,
                journalEntryRepository,
                taskRepository,
                deliverableRepository,
                candidateRepository,
                userRepository,
                employeeRepository,
                auditService
        );
        when(aiCompletionClient.getModel()).thenReturn(FAKE_MODEL);
        when(aiAnalysisRepository.save(any(AiAnalysis.class))).thenAnswer(inv -> {
            AiAnalysis a = inv.getArgument(0);
            // The audit trail logs analysis.getId(); emulate the JPA-generated id.
            if (a.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            }
            return a;
        });
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());
        when(staffAssistantAssembler.assemble(any(), anyString())).thenReturn(ASSEMBLED_WITH_FALLBACK);
    }

    @Test
    @DisplayName("returns the deterministic aggregates answer when AI is unavailable")
    void returnsDeterministicFallback_whenAiUnavailable() {
        when(aiCompletionClient.complete(anyString(), anyList()))
                .thenReturn(AiCompletionResult.failure(ERROR_MESSAGE, FAKE_MODEL, PROVIDER));

        CandidateAssistantQueryRequest request = new CandidateAssistantQueryRequest("Combien de candidatures en attente ?");
        AiAnalysisResultResponse response = service.queryStaffAssistant(request, ACTOR);

        assertThat(response).isNotNull();
        assertThat(response.responseText())
                .startsWith("Synthèse de la plateforme")
                .doesNotContain("indisponible")
                .doesNotContain(ERROR_MESSAGE);
        // Still persisted + audited with the unavailable outcome for traceability.
        verify(aiAnalysisRepository, atLeastOnce()).save(any(AiAnalysis.class));
        verify(auditService, atLeastOnce()).log(anyString(), anyString(), any(UUID.class), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("keeps the model answer when Gemini succeeds")
    void keepsModelAnswer_whenAiSucceeds() {
        when(aiCompletionClient.complete(anyString(), anyList()))
                .thenReturn(AiCompletionResult.success("Il y a 3 candidatures en attente.", FAKE_MODEL, PROVIDER));

        CandidateAssistantQueryRequest request = new CandidateAssistantQueryRequest("Combien de candidatures en attente ?");
        AiAnalysisResultResponse response = service.queryStaffAssistant(request, ACTOR);

        assertThat(response.responseText()).isEqualTo("Il y a 3 candidatures en attente.");
    }

    @Test
    @DisplayName("cinExcluded stays true on the persisted staff analysis")
    void cinExcludedIsAlwaysTrue() {
        when(aiCompletionClient.complete(anyString(), anyList()))
                .thenReturn(AiCompletionResult.failure(ERROR_MESSAGE, FAKE_MODEL, PROVIDER));

        service.queryStaffAssistant(new CandidateAssistantQueryRequest("progression ?"), ACTOR);

        verify(aiAnalysisRepository, atLeastOnce()).save(argThat(a ->
                a.getCinExcluded() && a.getType() == AiAnalysisType.STAFF_ASSISTANT_QUERY));
    }
}
