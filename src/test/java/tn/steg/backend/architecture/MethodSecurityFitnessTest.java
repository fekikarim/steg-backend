package tn.steg.backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.interfaces.rest.PublicEndpoint;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Architectural Fitness Test enforcing "Deny-by-Default" method security.
 * Every HTTP-mapped method in any {@code @RestController} MUST declare its security requirement
 * (e.g. {@code @PreAuthorize}, {@code @Secured}, {@code @RolesAllowed}) or be explicitly
 * marked with {@code @PublicEndpoint}.
 */
@AnalyzeClasses(packages = "tn.steg.backend", importOptions = ImportOption.DoNotIncludeTests.class)
public class MethodSecurityFitnessTest {

    @ArchTest
    public static final ArchRule controllerEndpointsMustBeSecuredOrDefaultDenied = classes()
            .that().areAnnotatedWith(RestController.class)
            .should(haveAllEndpointsSecuredOrExplicitlyPublic())
            .allowEmptyShould(true)
            .because("STEG security policy mandates deny-by-default: every endpoint must be explicitly secured or explicitly declared public via @PublicEndpoint.");

    private static ArchCondition<JavaClass> haveAllEndpointsSecuredOrExplicitlyPublic() {
        return new ArchCondition<>("have all endpoint methods secured or explicitly declared public") {
            @Override
            public void check(JavaClass controllerClass, ConditionEvents events) {
                boolean classSecured = controllerClass.isAnnotatedWith(PreAuthorize.class)
                        || controllerClass.isAnnotatedWith(Secured.class)
                        || controllerClass.isAnnotatedWith(RolesAllowed.class);
                boolean classPublic = controllerClass.isAnnotatedWith(PublicEndpoint.class);

                if (classSecured || classPublic) {
                    return;
                }

                for (JavaMethod method : controllerClass.getMethods()) {
                    if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
                        continue;
                    }
                    boolean isEndpoint = method.isAnnotatedWith(RequestMapping.class)
                            || method.isAnnotatedWith(GetMapping.class)
                            || method.isAnnotatedWith(PostMapping.class)
                            || method.isAnnotatedWith(PutMapping.class)
                            || method.isAnnotatedWith(DeleteMapping.class)
                            || method.isAnnotatedWith(PatchMapping.class);

                    if (!isEndpoint) {
                        continue;
                    }

                    boolean methodSecured = method.isAnnotatedWith(PreAuthorize.class)
                            || method.isAnnotatedWith(Secured.class)
                            || method.isAnnotatedWith(RolesAllowed.class);
                    boolean methodPublic = method.isAnnotatedWith(PublicEndpoint.class);

                    boolean satisfied = methodSecured || methodPublic;
                    String message = String.format(
                            "Endpoint method %s.%s() has no security annotation and is not annotated with @PublicEndpoint",
                            controllerClass.getSimpleName(), method.getName());
                    events.add(new SimpleConditionEvent(method, satisfied, message));
                }
            }
        };
    }
}
