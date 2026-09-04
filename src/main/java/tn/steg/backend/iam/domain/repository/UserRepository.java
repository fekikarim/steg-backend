package tn.steg.backend.iam.domain.repository;

import tn.steg.backend.iam.domain.model.User;

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
}
