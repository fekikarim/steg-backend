package tn.steg.backend.ai.infrastructure.assembler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.ai.domain.assembler.AssembledAiContent;
import tn.steg.backend.ai.domain.assembler.LogbookContentAssembler;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.companion.domain.model.Deliverable;
import tn.steg.backend.companion.domain.model.JournalEntry;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.repository.DeliverableRepository;
import tn.steg.backend.companion.domain.repository.InternshipJournalRepository;
import tn.steg.backend.companion.domain.repository.JournalEntryRepository;
import tn.steg.backend.companion.domain.repository.TaskRepository;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.repository.InternshipRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Assembler for LOGBOOK_GENERATION.
 *
 * <p>Uses actual recorded Phase A7 data:
 * - JournalEntry
 * - Task
 * - Deliverable
 *
 * Output is a structured draft logbook summary that the intern/supervisor can review and edit.
 * Does NOT access or include CIN/identity documents.
 */
@Component
@RequiredArgsConstructor
public class LogbookAiContentAssembler implements LogbookContentAssembler {

    private final InternshipRepository internshipRepository;
    private final InternshipJournalRepository journalRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final TaskRepository taskRepository;
    private final DeliverableRepository deliverableRepository;

    @Override
    public AssembledAiContent assemble(UUID internshipId, Void context) {
        Internship internship = internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));

        List<Task> tasks = taskRepository.findByInternshipId(internshipId);
        List<Deliverable> deliverables = deliverableRepository.findByInternshipId(internshipId);

        List<JournalEntry> entries = new ArrayList<>();
        journalRepository.findByInternshipId(internshipId).ifPresent(journal -> {
            entries.addAll(journalEntryRepository.findByJournalId(journal.getId()));
        });

        String systemInstruction = """
                You are an assistant for STEG internship logbook generation (Carnet de Stage).
                Transform the provided journal entries, planned tasks, and submitted deliverables into a coherent,
                professional draft logbook in French.
                Clearly demarcate sections: Résumé exécutif, Objectifs & Tâches accomplies, Journal des activités, Livrables clés.
                Mark the output clearly as a DRAFT (PROJET DE CARNET DE STAGE) intended for review and validation by the supervisor.
                Do not invent or fabricate facts beyond the provided inputs.
                """;

        List<String> promptParts = new ArrayList<>();
        promptParts.add("Stage réf: " + internship.getReference());
        promptParts.add("Sujet: " + (internship.getSubject() != null ? internship.getSubject() : "Non renseigné"));
        promptParts.add("Type: " + internship.getType());
        promptParts.add("Période: du " + internship.getStartDate() + " au " + internship.getEndDate());
        promptParts.add("Niveau académique: " + (internship.getAcademicLevel() != null ? internship.getAcademicLevel() : "Non renseigné"));

        StringBuilder taskSb = new StringBuilder("Tâches enregistrées (" + tasks.size() + "):\n");
        for (Task t : tasks) {
            taskSb.append("- ").append(t.getTitle()).append(" [Statut: ").append(t.getStatus()).append("]\n");
        }
        promptParts.add(taskSb.toString());

        StringBuilder journalSb = new StringBuilder("Entrées de journal soumises (" + entries.size() + "):\n");
        for (JournalEntry je : entries) {
            journalSb.append("- Date: ").append(je.getEntryDate())
                    .append(" | Titre: ").append(je.getTitle())
                    .append(" | Statut: ").append(je.getStatus())
                    .append(" | Description: ").append(je.getDescription())
                    .append("\n");
        }
        promptParts.add(journalSb.toString());

        StringBuilder delivSb = new StringBuilder("Livrables enregistrés (" + deliverables.size() + "):\n");
        for (Deliverable d : deliverables) {
            delivSb.append("- ").append(d.getTitle()).append(" [Statut: ").append(d.getStatus()).append("]\n");
        }
        promptParts.add(delivSb.toString());

        promptParts.add("Tâche requise: Générer la synthèse préliminaire du carnet de stage (brouillon de synthèse).");

        String inputSummary = String.format("Internship id=%s, tasks=%d, journalEntries=%d, deliverables=%d",
                internshipId, tasks.size(), entries.size(), deliverables.size());

        // cinExcluded is unconditionally true
        return new AssembledAiContent(systemInstruction, promptParts, inputSummary, true);
    }
}
