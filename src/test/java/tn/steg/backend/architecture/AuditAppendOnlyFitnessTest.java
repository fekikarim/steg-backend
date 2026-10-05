package tn.steg.backend.architecture;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.annotation.Annotation;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S9 append-only audit trail (AGENTS.md §8.2: "No edit or delete, ever").
 *
 * <p>Two layers: ArchUnit rules fail the build if a mutating HTTP mapping or
 * a mutating repository method ever appears in the audit context, and a
 * plain test pins the domain port's exact method vocabulary.
 */
@AnalyzeClasses(packages = "tn.steg.backend", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("S9 — audit trail is append-only")
class AuditAppendOnlyFitnessTest {

    private static ArchRule noMutatingMappings(
            Class<? extends Annotation> mapping, String verb) {
        return methods()
                .that().areDeclaredInClassesThat().resideInAPackage("tn.steg.backend.audit..")
                .should(new ArchCondition<JavaMethod>("not be @" + verb + "-mapped") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        boolean violated = method.isAnnotatedWith(mapping);
                        events.add(new SimpleConditionEvent(method, !violated,
                                violated
                                        ? "Audit API must stay read-only: " + method.getFullName()
                                                + " is @" + verb + "-mapped"
                                        : "OK"));
                    }
                })
                .because("the audit trail is append-only (§8.2): no edit or delete, ever");
    }

    @ArchTest
    static final ArchRule noPutInAudit = noMutatingMappings(PutMapping.class, "PutMapping");

    @ArchTest
    static final ArchRule noPatchInAudit = noMutatingMappings(PatchMapping.class, "PatchMapping");

    @ArchTest
    static final ArchRule noDeleteInAudit = noMutatingMappings(DeleteMapping.class, "DeleteMapping");

    @ArchTest
    static final ArchRule auditPortHasNoMutatingMethods = methods()
            .that().areDeclaredInClassesThat()
            .resideInAPackage("tn.steg.backend.audit.domain.repository..")
            .should(new ArchCondition<JavaMethod>("not mutate stored rows") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    String name = method.getName();
                    boolean violated = name.startsWith("delete") || name.startsWith("remove")
                            || name.startsWith("update") || name.equals("saveAll")
                            || name.equals("deleteAll");
                    events.add(new SimpleConditionEvent(method, !violated,
                            violated
                                    ? "Audit port must stay append-only: " + method.getFullName()
                                    : "OK"));
                }
            })
            .because("the audit store is append-only: insert + read, never update or delete");

    @Test
    @DisplayName("domain port vocabulary is exactly save + reads")
    void portVocabularyIsAppendOnly() {
        List<String> names = java.util.Arrays.stream(
                        tn.steg.backend.audit.domain.repository.AuditLogRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .sorted()
                .toList();
        assertThat(names).containsExactlyInAnyOrder(
                "save", "findById",
                "findByEntityTypeAndEntityIdOrderByCreatedAtAsc",
                "findAll", "findByAction", "findByEntityId", "findByActorId",
                "searchAudit");
    }
}
