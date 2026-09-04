package tn.steg.backend.application.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.organization.domain.model.Employee;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "internship_applications")
public class InternshipApplication extends BaseEntity {

    @Column(name = "reference", nullable = false, unique = true, length = 50)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ApplicationStatus status = ApplicationStatus.DRAFT;

    @Column(name = "submitted_online", nullable = false)
    private Boolean submittedOnline = true;

    @Column(name = "submission_date")
    private LocalDate submissionDate;

    @Column(name = "desired_start_date")
    private LocalDate desiredStartDate;

    @Column(name = "desired_end_date")
    private LocalDate desiredEndDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculated_type", length = 50)
    private InternshipType calculatedType;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement", length = 50)
    private InternshipRequirement requirement;

    @Column(name = "proposed_theme", columnDefinition = "TEXT")
    private String proposedTheme;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "correction_comment", columnDefinition = "TEXT")
    private String correctionComment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    private Employee reviewer;

    public InternshipApplication(String reference, Candidate candidate, ApplicationStatus status) {
        this.reference = reference;
        this.candidate = candidate;
        this.status = status;
    }
}
