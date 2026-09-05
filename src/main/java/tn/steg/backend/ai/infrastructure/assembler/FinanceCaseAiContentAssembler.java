package tn.steg.backend.ai.infrastructure.assembler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tn.steg.backend.ai.domain.assembler.AssembledAiContent;
import tn.steg.backend.ai.domain.assembler.FinanceCaseContentAssembler;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.document.domain.model.FinanceCaseDocument;
import tn.steg.backend.document.domain.repository.FinanceCaseDocumentRepository;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.finance.domain.model.PaymentCalculation;
import tn.steg.backend.finance.domain.repository.FinanceCaseRepository;
import tn.steg.backend.finance.domain.repository.PaymentCalculationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Assembler for FINANCE_CASE_ANALYSIS.
 *
 * <p>Critical Security Rule:
 * Queries documents strictly via {@code findByFinanceCaseIdAndDocumentRestrictedAccessFalse(financeCaseId)},
 * ensuring that CIN/identity documents are structurally excluded at the repository query level.
 */
@Component
@RequiredArgsConstructor
public class FinanceCaseAiContentAssembler implements FinanceCaseContentAssembler {

    private final FinanceCaseRepository financeCaseRepository;
    private final FinanceCaseDocumentRepository financeCaseDocumentRepository;
    private final PaymentCalculationRepository paymentCalculationRepository;

    @Override
    public AssembledAiContent assemble(UUID financeCaseId, Void context) {
        FinanceCase financeCase = financeCaseRepository.findById(financeCaseId)
                .orElseThrow(() -> new ResourceNotFoundException("FinanceCase not found: " + financeCaseId));

        // STRUCTURAL GUARANTEE: query excludes restrictedAccess=true at the database level!
        List<FinanceCaseDocument> eligibleDocs =
                financeCaseDocumentRepository.findByFinanceCaseIdAndDocumentRestrictedAccessFalse(financeCaseId);

        Optional<PaymentCalculation> calcOpt = paymentCalculationRepository.findByFinanceCaseId(financeCaseId);

        String systemInstruction = """
                You are an AI advisory financial assistant for STEG (Société Tunisienne de l'Electricité et du Gaz).
                Your role is strictly advisory. You analyze finance cases (indemnités de stage) to summarize dossier completion,
                document validity, and payment calculation details for human Finance Officers.
                You CANNOT approve or reject payments, and you cannot issue payment receipts.
                Respond with concise observations in French.
                """;

        List<String> promptParts = new ArrayList<>();
        promptParts.add("Dossier Financier STEG Référence: " + financeCase.getReference());
        promptParts.add("Statut du dossier: " + financeCase.getStatus());
        if (financeCase.getInternship() != null) {
            promptParts.add("Stage réf: " + financeCase.getInternship().getReference()
                    + " [" + financeCase.getInternship().getType() + "]");
            if (financeCase.getInternship().getSubject() != null) {
                promptParts.add("Sujet: " + financeCase.getInternship().getSubject());
            }
            promptParts.add("Période: du " + financeCase.getInternship().getStartDate()
                    + " au " + financeCase.getInternship().getEndDate());
        }

        if (calcOpt.isPresent()) {
            PaymentCalculation s = calcOpt.get();
            promptParts.add(String.format("Calcul d'indemnité: Taux mensuel = %s %s, Mois éligibles = %d, Montant total calculé = %s %s",
                    s.getRatePerMonth(), s.getCurrencyCode(), s.getPayableMonths(), s.getCalculatedAmount(), s.getCurrencyCode()));
        }

        StringBuilder docSummary = new StringBuilder();
        docSummary.append("Pièces justificatives non restreintes associées (").append(eligibleDocs.size()).append("):\n");
        for (FinanceCaseDocument fd : eligibleDocs) {
            docSummary.append("- Type: ").append(fd.getDocument().getType())
                    .append(", Réf: ").append(fd.getDocument().getReference())
                    .append(", Statut vérification: ").append(fd.getVerificationStatus())
                    .append("\n");
        }
        promptParts.add(docSummary.toString());
        promptParts.add("Tâche requise: Fournir un résumé pour l'agent financier, vérifier la complétude des pièces non restreintes et formuler une recommandation consultative.");

        String inputSummary = String.format("FinanceCase ref=%s, status=%s, non-restricted docs count=%d, calculationPresent=%s",
                financeCase.getReference(), financeCase.getStatus(), eligibleDocs.size(), calcOpt.isPresent());

        // cinExcluded is unconditionally true
        return new AssembledAiContent(systemInstruction, promptParts, inputSummary, true);
    }
}
