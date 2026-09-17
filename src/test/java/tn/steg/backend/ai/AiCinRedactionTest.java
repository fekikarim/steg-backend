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
import tn.steg.backend.ai.infrastructure.assembler.CandidateAssistantAiContentAssembler;
import tn.steg.backend.ai.infrastructure.assembler.FinanceCaseAiContentAssembler;
import tn.steg.backend.ai.infrastructure.assembler.InternAssistantAiContentAssembler;
import tn.steg.backend.ai.infrastructure.assembler.LogbookAiContentAssembler;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.model.University;
import tn.steg.backend.common.infrastructure.config.JpaAuditingConfig;
import tn.steg.backend.document.domain.model.ApplicationDocument;
import tn.steg.backend.document.domain.model.Document;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase E5: CIN & Identity Redaction Integration Test across all AI assemblers.
 *
 * <p>Proves at the database and assembly level that:
 * <ul>
 *   <li>Candidate Assistant never includes candidate CIN hash in prompts or inputSummary.</li>
 *   <li>Intern Assistant never includes candidate CIN hash in prompts or inputSummary.</li>
 *   <li>Application Document Assembler strictly filters out CIN_COPY documents.</li>
 *   <li>Logbook Assembler never includes candidate CIN hash in prompts or inputSummary.</li>
 *   <li>All 5 assemblers set {@code cinExcluded = true} unconditionally.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
@Transactional
@DisplayName("E5 — CIN & Identity Redaction across all AI Assemblers")
class AiCinRedactionTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private CandidateAssistantAiContentAssembler candidateAssembler;

    @Autowired
    private InternAssistantAiContentAssembler internAssembler;

    @Autowired
    private ApplicationDocumentAiContentAssembler applicationAssembler;

    @Autowired
    private FinanceCaseAiContentAssembler financeCaseAssembler;

    @Autowired
    private LogbookAiContentAssembler logbookAssembler;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Candidate Assistant: CIN hash is strictly excluded from prompt parts and input summary")
    void candidateAssistant_cinHashExcluded() {
        String suffix = uid();
        String sensitiveCinHash = "CIN_HASH_SECRET_" + suffix;
        User user = new User("cin_cand_" + suffix + "@steg.tn", "secretPass", UserStatus.ACTIVE);
        em.persist(user);
        University uni = new University("U_" + suffix, "Uni " + suffix);
        em.persist(uni);
        Candidate candidate = new Candidate("Amira", "Ben Salah",
                "amira_" + suffix + "@steg.tn", sensitiveCinHash, uni);
        candidate.setUser(user);
        em.persist(candidate);
        em.flush();

        AssembledAiContent assembled = candidateAssembler.assemble(candidate.getId(), "Quel est le statut de mon dossier ?");

        assertThat(assembled.cinExcluded()).isTrue();
        String promptString = String.join("\n", assembled.promptParts());
        assertThat(promptString).doesNotContain(sensitiveCinHash);
        assertThat(promptString).doesNotContain("secretPass");
        assertThat(assembled.inputSummary()).doesNotContain(sensitiveCinHash);
        assertThat(assembled.systemInstruction()).contains("Never disclose or ask for CIN");
    }

    @Test
    @DisplayName("Intern Assistant: Candidate CIN hash is strictly excluded from prompt parts and input summary")
    void internAssistant_cinHashExcluded() {
        String suffix = uid();
        String sensitiveCinHash = "CIN_HASH_SECRET_" + suffix;
        User user = new User("cin_intern_" + suffix + "@steg.tn", "secretPass", UserStatus.ACTIVE);
        em.persist(user);
        University uni = new University("U_" + suffix, "Uni " + suffix);
        em.persist(uni);
        Candidate candidate = new Candidate("Mehdi", "Trabelsi",
                "mehdi_" + suffix + "@steg.tn", sensitiveCinHash, uni);
        candidate.setUser(user);
        em.persist(candidate);

        Internship internship = new Internship("INT-CIN-" + suffix, candidate,
                LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(1),
                InternshipType.PFE, InternshipRequirement.OBLIGATOIRE);
        internship.setStatus(InternshipStatus.ACTIVE);
        em.persist(internship);
        em.flush();

        AssembledAiContent assembled = internAssembler.assemble(user.getId(), "Comment rédiger mes livrables ?");

        assertThat(assembled.cinExcluded()).isTrue();
        String promptString = String.join("\n", assembled.promptParts());
        assertThat(promptString).doesNotContain(sensitiveCinHash);
        assertThat(promptString).doesNotContain("secretPass");
        assertThat(assembled.inputSummary()).doesNotContain(sensitiveCinHash);
        assertThat(assembled.systemInstruction()).contains("Never disclose CIN");
    }

    @Test
    @DisplayName("Application Document Assembler: CIN_COPY document is excluded at DB query level")
    void applicationDocumentAssembler_cinDocExcluded() {
        String suffix = uid();
        String sensitiveCinHash = "CIN_HASH_SECRET_" + suffix;
        User user = new User("cin_app_" + suffix + "@steg.tn", "pass", UserStatus.ACTIVE);
        em.persist(user);
        University uni = new University("U_" + suffix, "Uni " + suffix);
        em.persist(uni);
        Candidate candidate = new Candidate("Sami", "Khelifi",
                "sami_" + suffix + "@steg.tn", sensitiveCinHash, uni);
        candidate.setUser(user);
        em.persist(candidate);

        InternshipApplication app = new InternshipApplication("APP-CIN-" + suffix, candidate, ApplicationStatus.SUBMITTED);
        em.persist(app);

        Document cvDoc = new Document("DOC-CV-" + suffix, DocumentType.CV);
        cvDoc.setRestrictedAccess(false);
        em.persist(cvDoc);

        Document cinDoc = new Document("DOC-CIN-" + suffix, DocumentType.CIN_COPY);
        cinDoc.setRestrictedAccess(true);
        em.persist(cinDoc);

        em.persist(new ApplicationDocument(app, cvDoc, true));
        em.persist(new ApplicationDocument(app, cinDoc, true));
        em.flush();
        em.clear();

        AssembledAiContent assembled = applicationAssembler.assemble(app.getId(), null);

        assertThat(assembled.cinExcluded()).isTrue();
        String promptString = String.join("\n", assembled.promptParts());
        assertThat(promptString).contains("DOC-CV-" + suffix);
        assertThat(promptString).doesNotContain("DOC-CIN-" + suffix);
        assertThat(promptString).doesNotContain(sensitiveCinHash);
        assertThat(assembled.inputSummary()).contains("non-restricted docs count=1");
        assertThat(assembled.inputSummary()).doesNotContain("DOC-CIN-" + suffix);
    }

    @Test
    @DisplayName("Logbook Assembler: Candidate CIN is not included in prompt parts or input summary")
    void logbookAssembler_cinExcluded() {
        String suffix = uid();
        String sensitiveCinHash = "CIN_HASH_SECRET_" + suffix;
        User user = new User("cin_lb_" + suffix + "@steg.tn", "pass", UserStatus.ACTIVE);
        em.persist(user);
        University uni = new University("U_" + suffix, "Uni " + suffix);
        em.persist(uni);
        Candidate candidate = new Candidate("Yassine", "Gharbi",
                "yassine_" + suffix + "@steg.tn", sensitiveCinHash, uni);
        candidate.setUser(user);
        em.persist(candidate);

        Internship internship = new Internship("INT-LB-" + suffix, candidate,
                LocalDate.now().minusMonths(2), LocalDate.now().plusMonths(1),
                InternshipType.PFE, InternshipRequirement.OBLIGATOIRE);
        internship.setStatus(InternshipStatus.ACTIVE);
        em.persist(internship);
        em.flush();

        AssembledAiContent assembled = logbookAssembler.assemble(internship.getId(), null);

        assertThat(assembled.cinExcluded()).isTrue();
        String promptString = String.join("\n", assembled.promptParts());
        assertThat(promptString).doesNotContain(sensitiveCinHash);
        assertThat(assembled.inputSummary()).doesNotContain(sensitiveCinHash);
    }
}
