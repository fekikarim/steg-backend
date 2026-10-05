package tn.steg.backend.iam.application.port.out;

import java.util.List;
import java.util.UUID;

public interface TokenServicePort {

    String generateAccessToken(UUID userId, String email, List<String> roles);

    /** Overload that embeds {@code mustChangePassword} as a JWT claim. */
    String generateAccessToken(UUID userId, String email, List<String> roles, boolean mustChangePassword);

    String generateRefreshToken();

    long getAccessTokenExpirationSeconds();

    long getRefreshTokenExpirationSeconds();

    String hashToken(String rawToken);
}
