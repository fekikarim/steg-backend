package tn.steg.backend.ai.infrastructure.assembler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.ai.domain.assembler.AssembledAiContent;
import tn.steg.backend.ai.domain.assembler.CandidateAssistantContentAssembler;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Assembler for CANDIDATE_ASSISTANT_QUERY.
 *
 * <p>Scoped strictly to the authenticated candidate:
 * - Provides application status, non-restricted required documents, and general platform FAQ.
 * - Structurally excludes CIN, national ID, internal review comments, and other candidates' data.
 */
@Component
@RequiredArgsConstructor
public class CandidateAssistantAiContentAssembler implements CandidateAssistantContentAssembler {

    private final CandidateRepository candidateRepository;
    private final InternshipApplicationRepository applicationRepository;

    @Override
    public AssembledAiContent assemble(UUID candidateId, String userQuestion) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + candidateId));

        List<InternshipApplication> apps = applicationRepository.findByCandidateId(candidateId);

        String systemInstruction = """
                You are the STEG Candidate Assistant (Assistant Virtuel des Stages STEG).
                You provide polite, helpful guidance to candidates regarding their internship application status,
                general document requirements (CV, lettre d'affectation, convention de stage), and standard STEG internship rules.
                
                Strict Security & Privacy Guidelines:
                1. Never disclose or ask for CIN, national ID card numbers, or passwords.
                2. Never reveal internal reviewer comments, staff deliberation notes, or finance case calculations.
                3. Only discuss applications belonging to this specific candidate.
                4. Always advise the candidate that formal administrative decisions are taken by authorized STEG staff.
                Respond in French or Arabic as appropriate to the user query.
                """;

        List<String> promptParts = new ArrayList<>();
        promptParts.add("Candidat: " + candidate.getFirstName() + " " + candidate.getLastName());
        if (candidate.getUniversity() != null) {
            promptParts.add("Établissement: " + candidate.getUniversity().getName());
        }

        StringBuilder appSb = new StringBuilder("Dossiers de candidature déposés par ce candidat (" + apps.size() + "):\n");
        for (InternshipApplication app : apps) {
            appSb.append("- Réf: ").append(app.getReference())
                    .append(" | Statut: ").append(app.getStatus())
                    .append(" | Date dépôt: ").append(app.getSubmissionDate())
                    .append("\n");
        }
        promptParts.add(appSb.toString());
        promptParts.add("Question posée par le candidat:\n" + userQuestion);

        String inputSummary = String.format("Candidate id=%s, applicationsCount=%d, questionLength=%d",
                candidateId, apps.size(), userQuestion != null ? userQuestion.length() : 0);

        // cinExcluded is unconditionally true
        return new AssembledAiContent(systemInstruction, promptParts, inputSummary, true);
    }
}
