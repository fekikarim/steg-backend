package tn.steg.backend.evaluation.application.dto;

/**
 * Request to create a new EvaluationTemplate.
 */
public record EvaluationTemplateRequest(
        String name,
        String description
) {}
