package tn.steg.backend.evaluation.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.repository.TaskRepository;
import tn.steg.backend.evaluation.application.dto.*;
import tn.steg.backend.evaluation.domain.model.*;
import tn.steg.backend.evaluation.domain.repository.*;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for the Evaluation module (A8).
 *
 * <p>Key business rules:
 * <ul>
 *   <li>Only the currently ACTIVE supervisor of the internship may create/submit evaluations.
 *   <li>totalScore is computed server-side using weighted average; rounding: HALF_UP to 2 decimals.
 *   <li>Each criterion score must be in [0, criterion.maxScore].
 *   <li>An EvaluationTaskReview can only be added once per (evaluation, task) pair.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final EvaluationDomainRepository evaluationRepository;
    private final EvaluationTemplateRepository templateRepository;
    private final EvaluationCriterionRepository criterionRepository;
    private final EvaluationScoreRepository scoreRepository;
    private final EvaluationTaskReviewRepository taskReviewRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final TaskRepository taskRepository;
    private final AuditService auditService;

    // -------------------------------------------------------------------------
    // Evaluation Templates (HR/ADMIN only)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EvaluationTemplateResponse> listTemplates(boolean activeOnly) {
        List<EvaluationTemplate> templates = activeOnly
                ? templateRepository.findAllActive()
                : templateRepository.findAll();
        return templates.stream().map(EvaluationTemplateResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EvaluationTemplateResponse getTemplate(UUID templateId) {
        return EvaluationTemplateResponse.from(findTemplateOrThrow(templateId));
    }

    @Transactional
    public EvaluationTemplateResponse createTemplate(EvaluationTemplateRequest request, UserPrincipal actor) {
        EvaluationTemplate template = new EvaluationTemplate(request.name(), request.description());
        template = templateRepository.save(template);
        auditService.log("EVALUATION_TEMPLATE_CREATED", "EvaluationTemplate", template.getId(), null, null, actor.getId(), null);
        log.info("EvaluationTemplate created: id={}, name={}, actor={}", template.getId(), template.getName(), actor.getId());
        return EvaluationTemplateResponse.from(template);
    }

    @Transactional
    public EvaluationTemplateResponse updateTemplate(UUID templateId, EvaluationTemplateRequest request, UserPrincipal actor) {
        EvaluationTemplate template = findTemplateOrThrow(templateId);
        template.setName(request.name());
        template.setDescription(request.description());
        template = templateRepository.save(template);
        auditService.log("EVALUATION_TEMPLATE_UPDATED", "EvaluationTemplate", template.getId(), null, null, actor.getId(), null);
        log.info("EvaluationTemplate updated: id={}, actor={}", templateId, actor.getId());
        return EvaluationTemplateResponse.from(template);
    }

    @Transactional
    public void deactivateTemplate(UUID templateId, UserPrincipal actor) {
        EvaluationTemplate template = findTemplateOrThrow(templateId);
        template.setActive(false);
        templateRepository.save(template);
        auditService.log("EVALUATION_TEMPLATE_DEACTIVATED", "EvaluationTemplate", templateId, null, null, actor.getId(), null);
        log.info("EvaluationTemplate deactivated: id={}, actor={}", templateId, actor.getId());
    }

    // -------------------------------------------------------------------------
    // Evaluation Criteria
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EvaluationCriterionResponse> listCriteria(UUID templateId) {
        findTemplateOrThrow(templateId);
        return criterionRepository.findByTemplateId(templateId)
                .stream().map(EvaluationCriterionResponse::from).toList();
    }

    @Transactional
    public EvaluationCriterionResponse addCriterion(UUID templateId, EvaluationCriterionRequest request, UserPrincipal actor) {
        EvaluationTemplate template = findTemplateOrThrow(templateId);
        validateCriterionWeight(request.weight());

        EvaluationCriterion criterion = new EvaluationCriterion(
                template, request.name(), request.weight(), request.maxScore());
        criterion.setDescription(request.description());
        criterion = criterionRepository.save(criterion);

        auditService.log("EVALUATION_CRITERION_ADDED", "EvaluationCriterion", criterion.getId(), null, null, actor.getId(), null);
        log.info("EvaluationCriterion added: id={}, template={}, actor={}", criterion.getId(), templateId, actor.getId());
        return EvaluationCriterionResponse.from(criterion);
    }

    @Transactional
    public EvaluationCriterionResponse updateCriterion(UUID criterionId, EvaluationCriterionRequest request, UserPrincipal actor) {
        EvaluationCriterion criterion = criterionRepository.findById(criterionId)
                .orElseThrow(() -> new ResourceNotFoundException("Criterion not found: " + criterionId));
        validateCriterionWeight(request.weight());

        criterion.setName(request.name());
        criterion.setDescription(request.description());
        criterion.setWeight(request.weight());
        criterion.setMaxScore(request.maxScore());
        criterion = criterionRepository.save(criterion);

        auditService.log("EVALUATION_CRITERION_UPDATED", "EvaluationCriterion", criterionId, null, null, actor.getId(), null);
        return EvaluationCriterionResponse.from(criterion);
    }

    // -------------------------------------------------------------------------
    // Evaluations
    // -------------------------------------------------------------------------

    @Transactional
    public EvaluationResponse createEvaluation(UUID internshipId, EvaluationRequest request, UserPrincipal actor) {
        Internship internship = findInternshipOrThrow(internshipId);
        Employee evaluator = resolveActiveSupervisor(internship, actor);

        EvaluationTemplate template = null;
        if (request.templateId() != null) {
            template = findTemplateOrThrow(request.templateId());
            if (!template.getActive()) {
                throw new BusinessRuleException("TEMPLATE_INACTIVE",
                        "Cannot use an inactive evaluation template.");
            }
        }

        LocalDate evalDate = request.evaluationDate() != null ? request.evaluationDate() : LocalDate.now();

        Evaluation evaluation = new Evaluation(internship, evaluator, request.type(), evalDate);
        evaluation.setTemplate(template);
        evaluation.setFeedback(request.feedback());
        evaluation = evaluationRepository.save(evaluation);

        // Audit: type change does not block creation but is always logged
        auditService.log("EVALUATION_CREATED_TYPE_" + request.type(), "Evaluation", evaluation.getId(), null, null, actor.getId(), null);
        log.info("Evaluation created: id={}, internship={}, type={}, evaluator={}", evaluation.getId(), internshipId, request.type(), evaluator.getId());
        return EvaluationResponse.from(evaluation);
    }

    @Transactional(readOnly = true)
    public Page<EvaluationResponse> listEvaluations(UUID internshipId, EvaluationType type, Pageable pageable) {
        findInternshipOrThrow(internshipId);
        return evaluationRepository.findByInternshipId(internshipId, pageable)
                .map(EvaluationResponse::from);
    }

    @Transactional(readOnly = true)
    public EvaluationResponse getEvaluation(UUID evaluationId) {
        return EvaluationResponse.from(findEvaluationOrThrow(evaluationId));
    }

    // -------------------------------------------------------------------------
    // Evaluation Scores
    // -------------------------------------------------------------------------

    /**
     * Add or update criterion scores for an evaluation, then recompute totalScore.
     * <p>The entire score list is replaced on each call (idempotent upsert behaviour).
     * Weighted total = Σ(score_i / maxScore_i * weight_i) / Σ(weight_i) * 20 — HALF_UP, 2dp.
     */
    @Transactional
    public List<EvaluationScoreResponse> submitScores(UUID evaluationId,
                                                       List<EvaluationScoreRequest> requests,
                                                       UserPrincipal actor) {
        Evaluation evaluation = findEvaluationOrThrow(evaluationId);
        assertActiveSupervisorOnEvaluation(evaluation, actor);

        if (requests == null || requests.isEmpty()) {
            throw new BusinessRuleException("EMPTY_SCORES", "At least one score must be submitted.");
        }

        // Remove old scores first (full replacement)
        List<EvaluationScore> existing = scoreRepository.findByEvaluationId(evaluationId);
        existing.forEach(s -> scoreRepository.deleteById(s.getId()));

        List<EvaluationScore> saved = requests.stream().map(req -> {
            EvaluationCriterion criterion = criterionRepository.findById(req.criterionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Criterion not found: " + req.criterionId()));

            validateScore(req.score(), criterion.getMaxScore());

            EvaluationScore score = new EvaluationScore(evaluation, criterion, req.score());
            score.setComment(req.comment());
            return scoreRepository.save(score);
        }).toList();

        // Recompute weighted total score
        BigDecimal total = computeWeightedTotal(saved);
        evaluation.setTotalScore(total);
        evaluationRepository.save(evaluation);

        auditService.log("EVALUATION_SCORES_SUBMITTED", "Evaluation", evaluationId, null, null, actor.getId(), null);
        log.info("Scores submitted: evaluation={}, count={}, totalScore={}, actor={}", evaluationId, saved.size(), total, actor.getId());
        return saved.stream().map(EvaluationScoreResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<EvaluationScoreResponse> listScores(UUID evaluationId) {
        findEvaluationOrThrow(evaluationId);
        return scoreRepository.findByEvaluationId(evaluationId)
                .stream().map(EvaluationScoreResponse::from).toList();
    }

    // -------------------------------------------------------------------------
    // Evaluation Task Reviews
    // -------------------------------------------------------------------------

    @Transactional
    public EvaluationTaskReviewResponse addTaskReview(UUID evaluationId,
                                                       EvaluationTaskReviewRequest request,
                                                       UserPrincipal actor) {
        Evaluation evaluation = findEvaluationOrThrow(evaluationId);
        assertActiveSupervisorOnEvaluation(evaluation, actor);

        Task task = taskRepository.findById(request.taskId())
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + request.taskId()));

        // Task must belong to the same internship
        if (!task.getInternship().getId().equals(evaluation.getInternship().getId())) {
            throw new BusinessRuleException("TASK_INTERNSHIP_MISMATCH",
                    "Task does not belong to the internship being evaluated.");
        }

        // Prevent duplicate review for same task within same evaluation
        if (taskReviewRepository.existsByEvaluationIdAndTaskId(evaluationId, request.taskId())) {
            throw new BusinessRuleException("DUPLICATE_TASK_REVIEW",
                    "A review for this task already exists in this evaluation.");
        }

        EvaluationTaskReview review = new EvaluationTaskReview(evaluation, task,
                request.completed() != null ? request.completed() : false);
        review.setScore(request.score());
        review.setComment(request.comment());
        review = taskReviewRepository.save(review);

        auditService.log("EVALUATION_TASK_REVIEW_ADDED", "EvaluationTaskReview", review.getId(), null, null, actor.getId(), null);
        log.info("TaskReview added: review={}, evaluation={}, task={}, actor={}", review.getId(), evaluationId, request.taskId(), actor.getId());
        return EvaluationTaskReviewResponse.from(review);
    }

    @Transactional(readOnly = true)
    public List<EvaluationTaskReviewResponse> listTaskReviews(UUID evaluationId) {
        findEvaluationOrThrow(evaluationId);
        return taskReviewRepository.findByEvaluationId(evaluationId)
                .stream().map(EvaluationTaskReviewResponse::from).toList();
    }

    // -------------------------------------------------------------------------
    // Weighted Score Computation
    // -------------------------------------------------------------------------

    /**
     * Computes a weighted average total score: Σ(score_i/maxScore_i * weight_i) / Σ(weight_i) * 20.
     * Rounds HALF_UP to 2 decimal places.
     * Returns null if no scores or if total weight is zero.
     */
    BigDecimal computeWeightedTotal(List<EvaluationScore> scores) {
        if (scores == null || scores.isEmpty()) {
            return null;
        }

        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;

        for (EvaluationScore s : scores) {
            BigDecimal maxScore = s.getCriterion().getMaxScore();
            BigDecimal weight = s.getCriterion().getWeight();

            if (maxScore == null || maxScore.compareTo(BigDecimal.ZERO) == 0 || weight == null) {
                continue;
            }

            // normalizedScore = score / maxScore * weight
            BigDecimal normalizedScore = s.getScore()
                    .divide(maxScore, 10, RoundingMode.HALF_UP)
                    .multiply(weight);

            weightedSum = weightedSum.add(normalizedScore);
            totalWeight = totalWeight.add(weight);
        }

        if (totalWeight.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // Scale to /20
        return weightedSum
                .divide(totalWeight, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(20))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Internship findInternshipOrThrow(UUID id) {
        return internshipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + id));
    }

    private EvaluationTemplate findTemplateOrThrow(UUID id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evaluation template not found: " + id));
    }

    private Evaluation findEvaluationOrThrow(UUID id) {
        return evaluationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evaluation not found: " + id));
    }

    /**
     * Resolves the active supervisor for the internship and asserts the actor is that supervisor.
     */
    private Employee resolveActiveSupervisor(Internship internship, UserPrincipal actor) {
        Optional<InternshipAssignment> activeAssignment =
                assignmentRepository.findByInternshipIdAndStatus(internship.getId(), AssignmentStatus.ACTIVE);

        if (activeAssignment.isEmpty()) {
            throw new BusinessRuleException("NO_ACTIVE_SUPERVISOR",
                    "This internship has no active supervisor assignment.");
        }

        Employee supervisor = activeAssignment.get().getSupervisor();
        if (supervisor == null || supervisor.getUser() == null
                || !supervisor.getUser().getId().equals(actor.getId())) {
            throw new AccessDeniedException(
                    "Only the currently active supervisor of this internship may create evaluations.");
        }
        return supervisor;
    }

    /**
     * Asserts the actor is the active supervisor of the internship linked to the evaluation.
     */
    private void assertActiveSupervisorOnEvaluation(Evaluation evaluation, UserPrincipal actor) {
        resolveActiveSupervisor(evaluation.getInternship(), actor);
    }

    private void validateScore(BigDecimal score, BigDecimal maxScore) {
        if (score == null) {
            throw new BusinessRuleException("NULL_SCORE", "Score cannot be null.");
        }
        if (score.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException("NEGATIVE_SCORE", "Score cannot be negative.");
        }
        if (maxScore != null && score.compareTo(maxScore) > 0) {
            throw new BusinessRuleException("SCORE_EXCEEDS_MAX",
                    "Score " + score + " exceeds max score " + maxScore + ".");
        }
    }

    private void validateCriterionWeight(BigDecimal weight) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("INVALID_WEIGHT", "Criterion weight must be a positive number.");
        }
    }
}
