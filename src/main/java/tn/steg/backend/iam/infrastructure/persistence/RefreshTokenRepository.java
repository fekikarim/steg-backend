package tn.steg.backend.iam.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.iam.domain.model.RefreshToken;

import java.util.UUID;

/**
 * Spring Data JPA adapter for the domain {@link tn.steg.backend.iam.domain.repository.RefreshTokenRepository} port.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID>, tn.steg.backend.iam.domain.repository.RefreshTokenRepository {
}
