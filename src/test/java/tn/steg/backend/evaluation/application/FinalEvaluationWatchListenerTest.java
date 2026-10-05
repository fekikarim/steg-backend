package tn.steg.backend.evaluation.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tn.steg.backend.common.domain.event.FinalEvaluationRequiredEvent;
import tn.steg.backend.common.domain.event.InternshipCompletedEvent;
import tn.steg.backend.evaluation.domain.model.EvaluationType;
import tn.steg.backend.evaluation.domain.repository.EvaluationDomainRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The evaluation module owns the "a final evaluation is required" rule: an
 * internship that completes without a FINAL report must raise the fact, and a
 * compliant internship must stay silent (no notification noise).
 */
@DisplayName("Final evaluation watch listener")
class FinalEvaluationWatchListenerTest {

    private final EvaluationDomainRepository evaluations = mock(EvaluationDomainRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final FinalEvaluationWatchListener listener = new FinalEvaluationWatchListener(evaluations, publisher);

    private InternshipCompletedEvent completion() {
        return new InternshipCompletedEvent(UUID.randomUUID(), "STAGE-2026-000042", "Sami Ben Ali", UUID.randomUUID());
    }

    @Test
    @DisplayName("completed internship without a FINAL report raises the requirement fact")
    void raisesFactWhenFinalIsMissing() {
        InternshipCompletedEvent event = completion();
        when(evaluations.findByInternshipIdAndType(eq(event.internshipId()), eq(EvaluationType.FINAL)))
                .thenReturn(List.of());

        listener.onInternshipCompleted(event);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(FinalEvaluationRequiredEvent.class);
        FinalEvaluationRequiredEvent raised = (FinalEvaluationRequiredEvent) captor.getValue();
        assertThat(raised.internshipId()).isEqualTo(event.internshipId());
        assertThat(raised.internshipReference()).isEqualTo("STAGE-2026-000042");
        assertThat(raised.candidateName()).isEqualTo("Sami Ben Ali");
    }

    @Test
    @DisplayName("completed internship already evaluated is never announced")
    void silentWhenFinalExists() {
        InternshipCompletedEvent event = completion();
        when(evaluations.findByInternshipIdAndType(eq(event.internshipId()), eq(EvaluationType.FINAL)))
                .thenReturn(List.of(mock(tn.steg.backend.evaluation.domain.model.Evaluation.class)));

        listener.onInternshipCompleted(event);

        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
