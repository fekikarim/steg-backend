package tn.steg.backend.internship.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.InternshipAssignment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Internship assignment details representation")
public record InternshipAssignmentResponse(
        UUID id,
        UUID internshipId,
        UUID departmentId,
        String departmentName,
        UUID supervisorId,
        String supervisorName,
        UUID assignedById,
        String assignedByName,
        LocalDate assignedAt,
        LocalDate startDate,
        LocalDate endDate,
        AssignmentStatus status,
        String assignmentReason,
        Instant endedAt,
        Instant createdAt,
        Long version
) {
    public static InternshipAssignmentResponse from(InternshipAssignment assignment) {
        String deptName = assignment.getDestination() != null ? assignment.getDestination().getName() : null;
        UUID deptId = assignment.getDestination() != null ? assignment.getDestination().getId() : null;

        String supName = assignment.getSupervisor() != null
                ? assignment.getSupervisor().getFirstName() + " " + assignment.getSupervisor().getLastName()
                : null;
        UUID supId = assignment.getSupervisor() != null ? assignment.getSupervisor().getId() : null;

        String assignerName = assignment.getAssignedBy() != null
                ? assignment.getAssignedBy().getFirstName() + " " + assignment.getAssignedBy().getLastName()
                : null;
        UUID assignerId = assignment.getAssignedBy() != null ? assignment.getAssignedBy().getId() : null;

        return new InternshipAssignmentResponse(
                assignment.getId(),
                assignment.getInternship() != null ? assignment.getInternship().getId() : null,
                deptId,
                deptName,
                supId,
                supName,
                assignerId,
                assignerName,
                assignment.getAssignedAt(),
                assignment.getStartDate(),
                assignment.getEndDate(),
                assignment.getStatus(),
                assignment.getAssignmentReason(),
                assignment.getEndedAt(),
                assignment.getCreatedAt(),
                assignment.getVersion()
        );
    }
}
