package tn.steg.backend.candidate.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateRepository {
    List<Candidate> findAll();
    Optional<Candidate> findById(UUID id);
    Optional<Candidate> findByUserId(UUID userId);
    Optional<Candidate> findByNationalIdHash(String nationalIdHash);

    /**
     * Live-only CIN lookup. Business code MUST use this (not
     * {@link #findByNationalIdHash}) so a soft-deleted profile is never
     * resurrected, reused or reported as taken: the tombstone keeps the hash
     * for audit, but the person is free to register again (V42, audit
     * assumption #13).
     */
    Optional<Candidate> findByNationalIdHashAndDeletedAtIsNull(String nationalIdHash);
    Optional<Candidate> findByEmail(String email);
    List<Candidate> findByEmailIgnoreCase(String email);
    List<Candidate> findByPhone(String phone);
    boolean existsByNationalIdHash(String nationalIdHash);

    /**
     * Live-only CIN check: uniqueness applies to profiles that are not
     * soft-deleted ({@code V42}, audit assumption #13), so a person whose
     * profile was deleted can register again with their own CIN while two live
     * profiles can still never share one.
     */
    boolean existsByNationalIdHashAndDeletedAtIsNull(String nationalIdHash);

    /** One LIVE profile per account; a soft-deleted tombstone does not block it. */
    boolean existsByUserIdAndDeletedAtIsNull(UUID userId);
    Candidate save(Candidate candidate);

    /**
     * LIVE candidates explicitly managed by a staff member (V44, AGENTS.md A4).
     * Used by {@code SupervisionScopeService} — the single owner of the
     * supervision-scope rule — to resolve "my candidates" for a profile that has
     * no internship yet. Soft-deleted rows are excluded: a tombstone is not a
     * candidate any more.
     */
    List<Candidate> findByManagedByIdAndDeletedAtIsNull(UUID managedByUserId);

    /** LIVE candidates for the given ids — the row form of a scoped list query. */
    List<Candidate> findByIdInAndDeletedAtIsNull(List<UUID> ids);

    /**
     * True when this LIVE candidate is explicitly managed by the given staff
     * member. The single-row form of {@link #findByManagedByIdAndDeletedAtIsNull}
     * used by the scope check (no row materialisation).
     */
    boolean existsByIdAndManagedByIdAndDeletedAtIsNull(UUID id, UUID managedByUserId);

    /**
     * Staff candidate queue (AGENTS.md §5.1 / §6.2): server-side pagination,
     * search and filters in a single query. Soft-deleted profiles
     * ({@code deletedAt != null}) are never returned.
     *
     * <p>Scope is part of the query, never of the client: {@code scoped=false}
     * is the Admin (all candidates), {@code scoped=true} restricts the result to
     * {@code candidateIds} (the Supervisor's own candidates). Callers must pass a
     * non-empty {@code candidateIds} list when {@code scoped=true}.
     *
     * @param universityName    exact university name in lower case, or {@code null}
     * @param searchPattern     SQL LIKE pattern (lower-cased) over full name,
     *                          email and university name, or {@code null}
     * @param accountActive     {@code TRUE}: linked account enabled;
     *                          {@code FALSE}: no linked account or disabled;
     *                          {@code null}: no filter
     * @param status            exact application status the candidate holds, or null
     * @param type              internship type (internship or its application), or null
     * @param supervisorUserId  supervisor user id (user-backed assignment or direct
     *                          internship link), or {@code null}
     * @param assignmentStatus  status used for the supervisor assignment match
     * @param createdFrom       inclusive lower bound on profile creation, or null
     * @param createdToExclusive exclusive upper bound on profile creation, or null
     */
    Page<Candidate> searchStaffCandidates(
            boolean scoped,
            List<UUID> candidateIds,
            String universityName,
            String searchPattern,
            Boolean accountActive,
            ApplicationStatus status,
            InternshipType type,
            UUID supervisorUserId,
            AssignmentStatus assignmentStatus,
            Instant createdFrom,
            Instant createdToExclusive,
            Pageable pageable);
}
