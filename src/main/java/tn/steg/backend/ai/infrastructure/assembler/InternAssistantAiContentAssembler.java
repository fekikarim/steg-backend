package tn.steg.backend.ai.infrastructure.assembler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.ai.domain.assembler.AssembledAiContent;
import tn.steg.backend.ai.domain.assembler.InternAssistantContentAssembler;
import tn.steg.backend.ai.domain.knowledge.StegKnowledgeBase;
import tn.steg.backend.ai.domain.knowledge.StegKnowledgeBase.Entry;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipRepository;

/**
 * Assembler for INTERN_ASSISTANT_QUERY (mobile intern/supervisor assistant).
 *
 * <p>Context = participant's ACTIVE internship (type, period, status) + retrieved
 * knowledge entries + the question. Never includes CIN, documents content, finance
 * calculations or other users' data. Never invents STEG rules: the model must
 * decline when no entry matches.
 */
@Component
@RequiredArgsConstructor
public class InternAssistantAiContentAssembler implements InternAssistantContentAssembler {

    private final InternshipRepository internshipRepository;
    private final StegKnowledgeBase knowledgeBase;

    @Override
    public AssembledAiContent assemble(UUID userId, String userQuestion) {
        List<Internship> active =
                internshipRepository.findByCandidateUserIdAndStatus(userId, InternshipStatus.ACTIVE);
        Internship internship = active.stream().findFirst().orElse(null);
        if (internship == null) {
            List<Internship> all = internshipRepository.findByCandidateId(userId);
            internship = all.stream().findFirst().orElseThrow(
                    () -> new ResourceNotFoundException("No internship found for user: " + userId));
        }

        List<Entry> entries = knowledgeBase.retrieve(userQuestion);

        String systemInstruction = """
                You are the STEG Intern Assistant (Assistant du Stagiaire STEG).
                Help the intern organise tasks, understand procedures and document their work.
                Rules:
                1. Answer ONLY from the official STEG sheets below plus the internship context.
                2. If the question is outside the sheets, decline politely (do not invent STEG policy).
                3. Never disclose CIN, passwords, reviewer notes, finance calculations or other users' data.
                4. Never invent tasks, dates or achievements. The logbook is built from recorded entries only.
                5. Mark every answer as advisory: the supervisor remains the authority.
                Respond in French or Arabic as appropriate to the user query.
                """;

        List<String> promptParts = new ArrayList<>();
        promptParts.add("Contexte stage — Réf: " + internship.getReference()
                + " | Type: " + internship.getType()
                + " | Statut: " + internship.getStatus()
                + " | Période: " + internship.getStartDate() + " → " + internship.getEndDate());
        promptParts.add(StegKnowledgeBase.renderContext(entries));
        promptParts.add("Question du stagiaire:\n" + userQuestion);

        String inputSummary = String.format("InternAssistant user=%s internship=%s kbEntries=%d questionLength=%d",
                userId, internship.getId(), entries.size(), userQuestion != null ? userQuestion.length() : 0);

        return new AssembledAiContent(systemInstruction, promptParts, inputSummary, true);
    }
}
