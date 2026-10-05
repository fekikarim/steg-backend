package tn.steg.backend.iam.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;

import java.util.UUID;

/**
 * Spring Data JPA adapter for the domain {@link tn.steg.backend.iam.domain.repository.UserRepository} port.
 * Spring will inject this bean wherever {@code UserRepository} (the domain port) is required,
 * because this interface extends both JpaRepository (giving it all CRUD operations)
 * and the domain port (making it type-compatible for injection).
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID>, tn.steg.backend.iam.domain.repository.UserRepository {
    // All required methods are declared in the domain port and implemented by JpaRepository or Spring Data derivation

    /**
     * Paged supervisor directory (AGENTS.md §5.6): server-side search/filter/
     * sort. DISTINCT keeps one row per user despite the role join.
     */
    @Query("SELECT DISTINCT u FROM User u JOIN u.assignedRoles r "
            + "WHERE r.code = 'SUPERVISOR' "
            + "AND (:searchPattern IS NULL OR LOWER(u.email) LIKE :searchPattern) "
            + "AND (:status IS NULL OR u.status = :status)")
    Page<User> findSupervisorUsers(
            @Param("searchPattern") String searchPattern,
            @Param("status") UserStatus status,
            Pageable pageable);

    /**
     * Paged 'STEG intern' accounts (AGENTS.md §5.4 + §5 list rule): students
     * (INTERN) and Supervisors, server-side role/status filters and email-or-
     * full-name search. {@code DISTINCT} keeps one row per user despite the
     * role join; the candidate join is a LEFT join so Supervisor accounts
     * (which have no candidate profile) are never dropped.
     */
    @Query("SELECT DISTINCT u FROM User u JOIN u.assignedRoles r "
            + "LEFT JOIN Candidate c ON c.user = u "
            + "WHERE (r.code = 'INTERN' OR r.code = 'SUPERVISOR') "
            + "AND (:role IS NULL OR r.code = :role) "
            + "AND (:status IS NULL OR u.status = :status) "
            + "AND (:searchPattern IS NULL OR LOWER(u.email) LIKE :searchPattern "
            + "     OR LOWER(CONCAT(c.firstName, ' ', c.lastName)) LIKE :searchPattern)")
    Page<User> findManagedAccounts(
            @Param("role") String role,
            @Param("status") UserStatus status,
            @Param("searchPattern") String searchPattern,
            Pageable pageable);
}
