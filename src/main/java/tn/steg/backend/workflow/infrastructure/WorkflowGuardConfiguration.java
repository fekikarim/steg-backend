package tn.steg.backend.workflow.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tn.steg.backend.workflow.domain.guard.ApplicationWorkflowGuard;
import tn.steg.backend.workflow.domain.guard.InternshipWorkflowGuard;
import tn.steg.backend.workflow.domain.guard.PaymentWorkflowGuard;

/**
 * Registers all WorkflowTransitionGuard implementations as Spring beans.
 * The WorkflowService receives them via constructor injection as a List.
 */
@Configuration
public class WorkflowGuardConfiguration {

    @Bean
    public ApplicationWorkflowGuard applicationWorkflowGuard() {
        return new ApplicationWorkflowGuard();
    }

    @Bean
    public InternshipWorkflowGuard internshipWorkflowGuard() {
        return new InternshipWorkflowGuard();
    }

    @Bean
    public PaymentWorkflowGuard paymentWorkflowGuard() {
        return new PaymentWorkflowGuard();
    }
}
