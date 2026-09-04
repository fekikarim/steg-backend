package tn.steg.backend.candidate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "candidate_academic_profiles")
public class CandidateAcademicProfile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false, unique = true)
    private Candidate candidate;

    @Enumerated(EnumType.STRING)
    @Column(name = "education_level", nullable = false, length = 50)
    private EducationLevel educationLevel;

    @Column(name = "academic_year", length = 50)
    private String academicYear;

    @Column(name = "speciality", length = 255)
    private String speciality;

    @Column(name = "diploma", length = 255)
    private String diploma;

    public CandidateAcademicProfile(Candidate candidate, EducationLevel educationLevel) {
        this.candidate = candidate;
        this.educationLevel = educationLevel;
    }
}
