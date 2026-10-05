package tn.steg.backend.iam.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port interface for User persistence operations.
 * The infrastructure.persistence.UserRepository extends both JpaRepository and this interface,
 * serving as the adapter (Ports & Adapters pattern).
 *
 * The application layer (AuthService) depends on this interface, not the concrete JPA repository,
 * preserving the clean architecture dependency rule.
 */
public interface UserRepository {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    User save(User user);
    Optional<User> findById(UUID id);
    List<User> findAll();
    void delete(User user);
    /** Staff holding a given role (used for role-scoped fan-out, e.g. admin alerts). */
    List<User> findDistinctByAssignedRoles_Code(String roleCode);

    /**
     * Server-side paged supervisor directory search (AGENTS.md §5 list rule):
     * SUPERVISOR-coded users only, optional lower-cased email LIKE pattern and
     * status filter, sorted/clamped by the caller's {@link Pageable}.
     */
    Page<User> findSupervisorUsers(String searchPattern, UserStatus status, Pageable pageable);

    /**
     * Server-side paged 'STEG intern' accounts (AGENTS.md §5.4 + §5 list rule):
     * INTERN/SUPERVISOR users only, optional role/status filters and an email
     * or full-name LIKE pattern, sorted/clamped by the caller's {@link Pageable}.
     */
    Page<User> findManagedAccounts(String role, UserStatus status, String searchPattern, Pageable pageable);
}
