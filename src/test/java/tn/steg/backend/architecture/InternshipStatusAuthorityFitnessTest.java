package tn.steg.backend.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import tn.steg.backend.internship.domain.model.Internship;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * S6b — ONE authority for the internship status.
 *
 * <p>Before S6b three different components wrote {@code Internship.status}: the
 * explicit lifecycle service, the legacy internship workflow engine (its
 * {@code ACTIVE}/{@code COMPLETED} steps) and {@code InternshipService} itself
 * (creation, auto-start on assignment, cancellation). Three writers meant three
 * different rule sets — a status could be reached that the §4 chain forbids.
 *
 * <p>This rule makes the invariant structural: {@code InternshipLifecycleService}
 * is the only class allowed to call {@code Internship.setStatus(...)}. Creation
 * goes through {@code initializeStatus}, §4 transitions through
 * {@code transition}, cancellation through {@code cancel} — all three in the
 * same authority.
 */
@AnalyzeClasses(packages = "tn.steg.backend", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("S6b — only the internship lifecycle authority writes Internship.status")
class InternshipStatusAuthorityFitnessTest {

    private static final String AUTHORITY_PACKAGE =
            "tn.steg.backend.internship.application";

    @ArchTest
    static final ArchRule onlyTheLifecycleAuthorityWritesInternshipStatus =
            noClasses()
                    .that().resideOutsideOfPackage(AUTHORITY_PACKAGE)
                    .should().callMethod(Internship.class, "setStatus",
                            tn.steg.backend.internship.domain.model.InternshipStatus.class)
                    .because("the internship status model must have exactly one writer, otherwise a status "
                            + "can be reached that the §4 chain forbids (S6b)");

    /**
     * The reverse guard: the authority must remain the writer (no dead rule).
     * Restored as an architecture check in S7 (it was a plain unit test in
     * S6b because the parameterless {@code callMethod} variant never matches
     * a Lombok setter — with the explicit {@code InternshipStatus} parameter
     * the rule is exact and green).
     */
    @ArchTest
    static final ArchRule theAuthorityDoesWriteInternshipStatus =
            ArchRuleDefinition.classes()
                    .that().haveSimpleName("InternshipLifecycleService")
                    .should().callMethod(Internship.class, "setStatus",
                            tn.steg.backend.internship.domain.model.InternshipStatus.class)
                    .because("otherwise the rule above would pass by accident if the authority stopped "
                            + "writing the status at all");
}