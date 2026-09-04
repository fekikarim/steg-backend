package tn.steg.backend.internship.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.common.domain.model.BaseEntity;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "internships")
public class Internship extends BaseEntity {

    @Column(name = "reference", nullable = false, unique = true, length = 50)
    private String reference;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private InternshipStatus status = InternshipStatus.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private InternshipType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement", nullable = false, length = 50)
    private InternshipRequirement requirement;

    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    @Column(name = "academic_level", length = 100)
    private String academicLevel;

    @Column(name = "planned_at")
    private Instant plannedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", unique = true)
    private InternshipApplication application;

    public Internship(String reference, Candidate candidate, LocalDate startDate, LocalDate endDate,
                      InternshipType type, InternshipRequirement requirement) {
        this.reference = reference;
        this.candidate = candidate;
        this.startDate = startDate;
        this.endDate = endDate;
        this.type = type;
        this.requirement = requirement;
    }
}
