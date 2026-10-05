package tn.steg.backend.iam.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.iam.domain.model.RefreshToken;

import java.util.UUID;

/**
 * Spring Data JPA adapter for the domain {@link tn.steg.backend.iam.domain.repository.RefreshTokenRepository} port.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID>, tn.steg.backend.iam.domain.repository.RefreshTokenRepository {

    @Override
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = CURRENT_TIMESTAMP WHERE r.user.id = :userId AND r.revokedAt IS NULL")
    void revokeAllForUser(@Param("userId") UUID userId);
}
