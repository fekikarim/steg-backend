package tn.steg.backend.evaluation.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tn.steg.backend.common.domain.event.FinalEvaluationRequiredEvent;
import tn.steg.backend.common.domain.event.InternshipCompletedEvent;
import tn.steg.backend.evaluation.domain.model.EvaluationType;
import tn.steg.backend.evaluation.domain.repository.EvaluationDomainRepository;

import java.util.UUID;

/**
 * Evaluation policy on internship completion: an internship that reaches
 * COMPLETED without a FINAL report is blocked for administrative validation,
 * so the fact "a final evaluation is required" is published and every consumer
 * (notifications for staff, live refresh for the Back Office) reacts to it.
 *
 * <p>The evaluation module owns this rule: it is the only module entitled to
 * decide whether a FINAL report exists.
 *
 * <p>Deliberately a PLAIN {@link EventListener}, not a
 * {@code @TransactionalEventListener(BEFORE_COMMIT)}: this watcher publishes a
 * second transactional event ({@link FinalEvaluationRequiredEvent}) and Spring
 * snapshots the transaction synchronizations before running the BEFORE_COMMIT
 * callbacks — a nested event registered from inside one would never run, and
 * the Admin alert would only surface on the hourly self-healing sweep. As a
 * plain listener it runs while the completing transaction is still open, so the
 * downstream notification listener registers normally and its fan-out still
 * commits atomically with the completion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinalEvaluationWatchListener {

    private final EvaluationDomainRepository evaluationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onInternshipCompleted(InternshipCompletedEvent event) {
        if (hasFinalEvaluation(event.internshipId())) {
            log.debug("Internship {} completed with a FINAL evaluation — no alert", event.internshipId());
            return;
        }
        log.info("Internship {} completed without a FINAL evaluation — alerting staff", event.internshipReference());
        eventPublisher.publishEvent(new FinalEvaluationRequiredEvent(
                event.internshipId(),
                event.internshipReference(),
                event.candidateName(),
                event.actorId()));
    }

    private boolean hasFinalEvaluation(UUID internshipId) {
        return !evaluationRepository.findByInternshipIdAndType(internshipId, EvaluationType.FINAL).isEmpty();
    }
}
