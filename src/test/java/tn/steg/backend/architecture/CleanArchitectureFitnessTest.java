package tn.steg.backend.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Architectural Fitness Tests enforcing Clean Architecture principles
 * and the Inward Dependency Rule across all STEG business modules.
 *
 * Uses `consideringOnlyDependenciesInLayers()` so that JDK types (String,
 * RuntimeException, java.util.*, etc.) and third-party libraries used
 * inside a layer do not produce false positives.  Only cross-layer
 * dependencies that violate the rule are reported.
 */
@AnalyzeClasses(packages = "tn.steg.backend", importOptions = ImportOption.DoNotIncludeTests.class)
public class CleanArchitectureFitnessTest {

    /**
     * Core layered architecture rule.
     * Checks that no layer calls upward/outward to a layer that should be
     * invisible to it.  External JDK / library classes are intentionally
     * excluded from violation detection via consideringOnlyDependenciesInLayers.
     */
    @ArchTest
    public static final ArchRule cleanArchitectureLayersRule = layeredArchitecture()
            // Only flag cross-layer violations; JDK / third-party deps are fine inside any layer
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("Infrastructure").definedBy("..infrastructure..")
            .layer("Interfaces").definedBy("..interfaces..")

            // Domain is the innermost ring — it may NOT access any other layer
            .whereLayer("Domain").mayNotAccessAnyLayer()

            // Application may only orchestrate Domain
            .whereLayer("Application").mayOnlyAccessLayers("Domain")

            // Infrastructure adapters implement Domain ports / use Application services
            .whereLayer("Infrastructure").mayOnlyAccessLayers("Domain", "Application")

            // Interfaces adapters invoke Application use cases / refer to Domain types
            .whereLayer("Interfaces").mayOnlyAccessLayers("Application", "Domain");

    /**
     * Domain must never import Spring Web or Servlet types.
     * This ensures the business core stays framework-free.
     */
    @ArchTest
    public static final ArchRule domainMustNotDependOnWeb = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..",
                    "jakarta.servlet..",
                    "org.springframework.security.web.."
            )
            .because("The domain layer must remain pure and free from Web and Servlet framework dependencies.");

    /**
     * REST controllers must reside in the interfaces layer.
     * Allows empty set during early phases when no controllers exist yet.
     */
    @ArchTest
    public static final ArchRule controllersMustResideInInterfaces = classes()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage("..interfaces..")
            .allowEmptyShould(true)
            .because("REST controllers are inbound adapters and must reside within the interfaces layer.");
}
