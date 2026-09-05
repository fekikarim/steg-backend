package tn.steg.backend.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural Fitness Tests enforcing the "strictly advisory" invariant
 * for the AI Assistance Layer (Phase A12).
 *
 * <h2>Design Intent</h2>
 * <p>The {@code ai.*} package must NEVER call into the mutation services of
 * other business modules.  Advisory output (AiAnalysis / AiRecommendation)
 * is the only state the AI layer may create.  All authoritative state changes
 * MUST be made by human-driven service calls outside this package.
 *
 * <h2>What is tested</h2>
 * <ol>
 *   <li>{@code ai.*} must not depend on any service in {@code application.*} or
 *       {@code candidate.application.*}, {@code internship.application.*},
 *       {@code finance.application.*}, {@code evaluation.application.*},
 *       {@code companion.application.*}, {@code workflow.application.*},
 *       {@code document.application.*}, {@code comment.application.*},
 *       {@code certificate.application.*}, or {@code organization.application.*}.
 *       The ONLY application service the AI layer is allowed to touch is
 *       {@code audit.application.AuditService} (read-write of audit trail).</li>
 *   <li>{@code ai.*} must not import CIN-related document types, specifically
 *       any class whose simple name contains "Cin".</li>
 * </ol>
 */
@AnalyzeClasses(packages = "tn.steg.backend", importOptions = ImportOption.DoNotIncludeTests.class)
public class AiIsolationFitnessTest {

    // -------------------------------------------------------------------------
    // Rule 1 – AI must not call mutation services of other business modules
    // -------------------------------------------------------------------------

    /**
     * The AI package may ONLY reference the audit service among all
     * application-layer services.  Specifically, it must not call into:
     * <ul>
     *   <li>ApplicationService   (application.application.*)</li>
     *   <li>InternshipService    (internship.application.*)</li>
     *   <li>FinanceService       (finance.application.*)</li>
     *   <li>EvaluationService    (evaluation.application.*)</li>
     *   <li>CompanionService     (companion.application.*)</li>
     *   <li>WorkflowService      (workflow.application.*)</li>
     *   <li>DocumentService      (document.application.*)</li>
     *   <li>CommentService       (comment.application.*)</li>
     *   <li>CandidateService     (candidate.application.*)</li>
     *   <li>CertificateService   (certificate.application.*)</li>
     *   <li>OrganizationService  (organization.application.*)</li>
     *   <li>MessagingService     (messaging.application.*)</li>
     *   <li>NotificationService  (notification.application.*)</li>
     *   <li>AuthService          (iam.application.*)</li>
     * </ul>
     */
    @ArchTest
    public static final ArchRule aiMustNotDependOnMutationServices = noClasses()
            .that().resideInAPackage("tn.steg.backend.ai..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "tn.steg.backend.application.application..",
                    "tn.steg.backend.internship.application..",
                    "tn.steg.backend.finance.application..",
                    "tn.steg.backend.evaluation.application..",
                    "tn.steg.backend.companion.application..",
                    "tn.steg.backend.workflow.application..",
                    "tn.steg.backend.document.application..",
                    "tn.steg.backend.comment.application..",
                    "tn.steg.backend.candidate.application..",
                    "tn.steg.backend.certificate.application..",
                    "tn.steg.backend.organization.application..",
                    "tn.steg.backend.messaging.application..",
                    "tn.steg.backend.notification.application..",
                    "tn.steg.backend.iam.application.."
            )
            .because("The AI layer is strictly advisory and must never call into the mutation " +
                    "services of other business modules.  Only audit.application.AuditService " +
                    "is permitted as a cross-module application dependency.");

    // -------------------------------------------------------------------------
    // Rule 2 – AI assemblers must not reach CIN / restricted document types
    // -------------------------------------------------------------------------

    /**
     * AI content assemblers (in {@code ai.infrastructure.assembler}) may not
     * directly import or reference any class whose simple name contains "Cin"
     * or is named "DocumentType" from the document domain (since DocumentType
     * includes the CIN_COPY variant — structural exclusion happens at the
     * repository query level instead).
     *
     * <p>This rule is a belt-and-suspenders check on top of the repository-level
     * query filtering ({@code restrictedAccess = false}) to prevent accidental
     * CIN content leakage into AI prompt context.
     */
    @ArchTest
    public static final ArchRule aiAssemblersMustNotReferenceCinTypes = noClasses()
            .that().resideInAPackage("tn.steg.backend.ai.infrastructure.assembler..")
            .should().dependOnClassesThat().haveSimpleNameContaining("Cin")
            .because("CIN content is structurally excluded from the AI context.  Assemblers must " +
                    "not reference any CIN-related type directly; exclusion is enforced at the " +
                    "repository query level via the restrictedAccess flag.");

    // -------------------------------------------------------------------------
    // Rule 3 – AI layer must not depend on document.application (write services)
    // -------------------------------------------------------------------------

    /**
     * Reinforces Rule 1 specifically for the document application layer, since
     * that is where file upload, CIN copy storage, and validation mutations live.
     */
    @ArchTest
    public static final ArchRule aiMustNotDependOnDocumentApplicationLayer = noClasses()
            .that().resideInAPackage("tn.steg.backend.ai..")
            .should().dependOnClassesThat().resideInAPackage("tn.steg.backend.document.application..")
            .because("The AI layer must not invoke document write/upload operations.  " +
                    "Document content is accessed read-only through the domain repository ports " +
                    "with restricted-access filtering applied.");
}
