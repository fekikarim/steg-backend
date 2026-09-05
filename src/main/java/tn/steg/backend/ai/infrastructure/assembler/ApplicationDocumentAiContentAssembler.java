package tn.steg.backend.ai.infrastructure.assembler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.ai.domain.assembler.ApplicationDocumentContentAssembler;
import tn.steg.backend.ai.domain.assembler.AssembledAiContent;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.document.domain.model.ApplicationDocument;
import tn.steg.backend.document.domain.repository.ApplicationDocumentRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Assembler for APPLICATION_DOCUMENT_ANALYSIS.
 *
 * <p>Critical Security Rule:
 * Queries documents strictly via {@code findByApplicationIdAndDocumentRestrictedAccessFalse(applicationId)},
 * ensuring that CIN/restricted identity records are structurally excluded at the repository query level.
 */
@Component
@RequiredArgsConstructor
public class ApplicationDocumentAiContentAssembler implements ApplicationDocumentContentAssembler {

    private final InternshipApplicationRepository applicationRepository;
    private final ApplicationDocumentRepository applicationDocumentRepository;

    @Override
    public AssembledAiContent assemble(UUID applicationId, Void context) {
        InternshipApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + applicationId));

        // STRUCTURAL GUARANTEE: query excludes restrictedAccess=true at the database level!
        List<ApplicationDocument> eligibleDocs =
                applicationDocumentRepository.findByApplicationIdAndDocumentRestrictedAccessFalse(applicationId);

        String systemInstruction = """
                You are an AI advisory assistant for STEG (Société Tunisienne de l'Electricité et du Gaz).
                Your role is strictly advisory. You analyze internship application files to highlight missing elements,
                summarize submitted academic credentials and requests, and propose recommendations for human HR reviewers.
                You NEVER make final decisions, and you CANNOT approve or reject applications.
                Respond with structured observations in French.
                """;

        List<String> promptParts = new ArrayList<>();
        promptParts.add("Dossier de candidature STEG Référence: " + app.getReference());
        promptParts.add("Statut actuel: " + app.getStatus());
        if (app.getCalculatedType() != null) {
            promptParts.add("Type de stage calculé: " + app.getCalculatedType());
        }
        if (app.getDesiredStartDate() != null && app.getDesiredEndDate() != null) {
            promptParts.add("Période souhaitée: du " + app.getDesiredStartDate() + " au " + app.getDesiredEndDate());
        }
        if (app.getProposedTheme() != null && !app.getProposedTheme().isBlank()) {
            promptParts.add("Thème proposé par le candidat: " + app.getProposedTheme());
        }

        StringBuilder docSummary = new StringBuilder();
        docSummary.append("Documents non restreints vérifiés dans le dossier (").append(eligibleDocs.size()).append("):\n");
        for (ApplicationDocument ad : eligibleDocs) {
            docSummary.append("- Type: ").append(ad.getDocument().getType())
                    .append(", Réf: ").append(ad.getDocument().getReference())
                    .append(", Statut vérification: ").append(ad.getVerificationStatus())
                    .append(", Obligatoire: ").append(ad.getMandatory())
                    .append("\n");
        }
        promptParts.add(docSummary.toString());
        promptParts.add("Tâche requise: Fournir un résumé du dossier, signaler d'éventuelles pièces manquantes non restreintes, et proposer des recommandations de vérification pour les agents RH.");

        String inputSummary = String.format("Application ref=%s, status=%s, non-restricted docs count=%d",
                app.getReference(), app.getStatus(), eligibleDocs.size());

        // cinExcluded is unconditionally true
        return new AssembledAiContent(systemInstruction, promptParts, inputSummary, true);
    }
}
