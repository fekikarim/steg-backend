package tn.steg.backend.iam.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.iam.domain.model.UserSession;

import java.util.UUID;

/**
 * Spring Data JPA adapter for the domain {@link tn.steg.backend.iam.domain.repository.UserSessionRepository} port.
 */
@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID>, tn.steg.backend.iam.domain.repository.UserSessionRepository {
}
