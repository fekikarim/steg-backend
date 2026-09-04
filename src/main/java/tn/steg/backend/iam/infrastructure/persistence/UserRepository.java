package tn.steg.backend.iam.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.iam.domain.model.User;

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
}
