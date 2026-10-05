package tn.steg.backend.workflow.application.dto;

import tn.steg.backend.internship.application.dto.InternshipResponse;

import java.util.UUID;

public record ApplicationApprovalResponse(
        WorkflowActionResponse workflowAction,
        InternshipResponse internship,
        UUID supervisorUserId,
        String credentialEmail,
        String temporaryPassword,
        boolean credentialEmailSent
) {
}