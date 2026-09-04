package tn.steg.backend.candidate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.iam.domain.model.User;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "candidates")
public class Candidate extends BaseEntity {

    @Column(name = "national_id_encrypted", columnDefinition = "TEXT")
    private String nationalIdEncrypted;

    @Column(name = "national_id_hash", nullable = false, unique = true)
    private String nationalIdHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "speciality", length = 255)
    private String speciality;

    @Column(name = "diploma", length = 255)
    private String diploma;

    @Column(name = "skills", columnDefinition = "TEXT")
    private String skills;

    @Column(name = "languages", columnDefinition = "TEXT")
    private String languages;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "university_id", nullable = false)
    private University university;

    public Candidate(String firstName, String lastName, String email, String nationalIdHash, University university) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.nationalIdHash = nationalIdHash;
        this.university = university;
    }
}
