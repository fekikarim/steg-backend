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

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "candidates")
public class Candidate extends BaseEntity {

    @Column(name = "national_id_encrypted", columnDefinition = "TEXT")
    private String nationalIdEncrypted;

    /**
     * SHA-256 of the CIN; the uniqueness anchor. NOT declared {@code unique}
     * because {@code V42} moved uniqueness to the LIVE rows only
     * ({@code idx_candidates_live_national_id_hash}, {@code WHERE deleted_at
     * IS NULL}): a soft-deleted profile must not reserve a permanent national
     * identity number forever. Two live profiles can never share a CIN.
     */
    @Column(name = "national_id_hash", nullable = false)
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

    /**
     * Soft delete (AGENTS.md §5.1, V41): a deleted profile keeps its row
     * (national_id_hash stays unique) but is excluded from every staff list,
     * detail and update. Deletion is refused while applications/internships
     * still reference the candidate.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "university_id", nullable = false)
    private University university;

    /**
     * Staff member who manages this candidate (V44, AGENTS.md §5.1 / §6.2 / A4).
     *
     * <p>{@code NULL} for every profile that came from the front office or the
     * anonymous intake — those are supervised through their internship
     * assignment. It is set when STAFF create the profile (AGENTS.md A4), which
     * is the only way a candidate exists before an internship does.
     *
     * <p>The scope RULE ("own candidate" = active assignment OR this link) lives
     * only in {@code SupervisionScopeService}; this field is data.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "managed_by_user_id")
    private User managedBy;

    public Candidate(String firstName, String lastName, String email, String nationalIdHash, University university) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.nationalIdHash = nationalIdHash;
        this.university = university;
    }
}
