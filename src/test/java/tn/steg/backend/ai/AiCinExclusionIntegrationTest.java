package tn.steg.backend.ai;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.TestcontainersConfiguration;
import tn.steg.backend.ai.domain.assembler.AssembledAiContent;
import tn.steg.backend.ai.infrastructure.assembler.ApplicationDocumentAiContentAssembler;
import tn.steg.backend.ai.infrastructure.assembler.FinanceCaseAiContentAssembler;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.document.domain.model.ApplicationDocument;
import tn.steg.backend.document.domain.model.Document;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.FinanceCaseDocument;
import tn.steg.backend.document.infrastructure.persistence.ApplicationDocumentRepository;
import tn.steg.backend.document.infrastructure.persistence.FinanceCaseDocumentRepository;
import tn.steg.backend.finance.domain.model.FinanceCase;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase A12 verification: CIN / restricted-document structural exclusion.
 *
 * <p>Proves at the database level that:
 * <ul>
 *   <li>{@code findByApplicationId} sees both normal + restricted rows,</li>
 *   <li>{@code findByApplicationIdAndDocumentRestrictedAccessFalse} hides the restricted row,</li>
 *   <li>the AI assemblers (which call ONLY the filtered method) can never receive the CIN ref.</li>
 * </ul>
 * Same proof for the finance-case dossier path.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("A12 — CIN / restricted-document exclusion (repository + assembler proof)")
class AiCinExclusionIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private ApplicationDocumentRepository applicationDocumentRepository;

    @Autowired
    private FinanceCaseDocumentRepository financeCaseDocumentRepository;

    @Autowired
    private ApplicationDocumentAiContentAssembler applicationAssembler;

    @Autowired
    private FinanceCaseAiContentAssembler financeCaseAssembler;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Candidate persistCandidate() {
        String suffix = uid();
        User user = new User("a12_" + suffix + "@steg.tn", "hash", UserStatus.ACTIVE);
        em.persist(user);
        University uni = new University("U_A12_" + suffix, "University A12 " + suffix);
        em.persist(uni);
        Candidate candidate = new Candidate("First" + suffix, "Last" + suffix,
                "cand_" + suffix + "@steg.tn", "HASH_A12_" + suffix, uni);
        candidate.setUser(user);
        em.persist(candidate);
        em.flush();
        return candidate;
    }

    private Document persistDocument(String ref, DocumentType type, boolean restricted) {
        Document document = new Document(ref, type);
        document.setRestrictedAccess(restricted);
        em.persist(document);
        em.flush();
        return document;
    }

    @Test
    @DisplayName("application assembler excludes CIN_COPY at DB level and in prompt content")
    void applicationAssemblerExcludesRestrictedDocuments() {
        Candidate candidate = persistCandidate();
        String appRef = "APP-A12-" + uid();
        InternshipApplication application =
                new InternshipApplication(appRef, candidate, ApplicationStatus.SUBMITTED);
        em.persist(application);

        String normalRef = "DOC-NORMAL-" + uid();
        String cinRef = "DOC-CIN-" + uid();
        Document normalDoc = persistDocument(normalRef, DocumentType.CV, false);
        Document cinDoc = persistDocument(cinRef, DocumentType.CIN_COPY, true);

        em.persist(new ApplicationDocument(application, normalDoc, true));
        em.persist(new ApplicationDocument(application, cinDoc, true));
        em.flush();
        em.clear();

        List<ApplicationDocument> all =
                applicationDocumentRepository.findByApplicationId(application.getId());
        assertThat(all).hasSize(2);

        List<ApplicationDocument> eligible =
                applicationDocumentRepository.findByApplicationIdAndDocumentRestrictedAccessFalse(
                        application.getId());
        assertThat(eligible).hasSize(1);
        assertThat(eligible.get(0).getDocument().getReference()).isEqualTo(normalRef);

        AssembledAiContent assembled = applicationAssembler.assemble(application.getId(), null);
        assertThat(assembled.cinExcluded()).isTrue();
        assertThat(assembled.inputSummary()).contains("non-restricted docs count=1");
        String joinedPrompts = String.join("\n", assembled.promptParts());
        assertThat(joinedPrompts).contains(normalRef);
        assertThat(joinedPrompts).doesNotContain(cinRef);
    }

    @Test
    @DisplayName("finance-case assembler excludes CIN_COPY at DB level and in prompt content")
    void financeCaseAssemblerExcludesRestrictedDocuments() {
        Candidate candidate = persistCandidate();
        String intRef = "INT-A12-" + uid();
        Internship internship = new Internship(intRef, candidate,
                LocalDate.now().minusMonths(4), LocalDate.now(),
                InternshipType.PFE, InternshipRequirement.OBLIGATOIRE);
        internship.setStatus(InternshipStatus.COMPLETED);
        em.persist(internship);

        String caseRef = "FIN-A12-" + uid();
        FinanceCase financeCase = new FinanceCase(caseRef, internship);
        em.persist(financeCase);

        String normalRef = "FDOC-NORMAL-" + uid();
        String cinRef = "FDOC-CIN-" + uid();
        Document normalDoc = persistDocument(normalRef, DocumentType.ASSIGNMENT_LETTER, false);
        Document cinDoc = persistDocument(cinRef, DocumentType.CIN_COPY, true);

        em.persist(new FinanceCaseDocument(financeCase, normalDoc, true));
        em.persist(new FinanceCaseDocument(financeCase, cinDoc, true));
        em.flush();
        em.clear();

        List<FinanceCaseDocument> all =
                financeCaseDocumentRepository.findByFinanceCaseId(financeCase.getId());
        assertThat(all).hasSize(2);

        List<FinanceCaseDocument> eligible =
                financeCaseDocumentRepository.findByFinanceCaseIdAndDocumentRestrictedAccessFalse(
                        financeCase.getId());
        assertThat(eligible).hasSize(1);
        assertThat(eligible.get(0).getDocument().getReference()).isEqualTo(normalRef);

        AssembledAiContent assembled = financeCaseAssembler.assemble(financeCase.getId(), null);
        assertThat(assembled.cinExcluded()).isTrue();
        assertThat(assembled.inputSummary()).contains("non-restricted docs count=1");
        String joinedPrompts = String.join("\n", assembled.promptParts());
        assertThat(joinedPrompts).contains(normalRef);
        assertThat(joinedPrompts).doesNotContain(cinRef);
    }
}
