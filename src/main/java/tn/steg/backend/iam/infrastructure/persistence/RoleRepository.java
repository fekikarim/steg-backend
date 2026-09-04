package tn.steg.backend.iam.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.iam.domain.model.Role;

import java.util.UUID;

/**
 * Spring Data JPA adapter for the domain {@link tn.steg.backend.iam.domain.repository.RoleRepository} port.
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, UUID>, tn.steg.backend.iam.domain.repository.RoleRepository {
}
