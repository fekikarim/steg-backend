package tn.steg.backend.iam.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Authentication response containing JWT tokens")
public class AuthResponse {

    @Schema(description = "JWT access token", example = "eyJhbGciOiJIUzUxMiJ9...")
    private String accessToken;

    @Schema(description = "Opaque refresh token", example = "dGhpcyBpcyBhIHJlZnJlc2ggdG9rZW4...")
    private String refreshToken;

    @Schema(description = "Access token expiration in seconds", example = "900")
    private long expiresIn;

    @Schema(description = "Token type", example = "Bearer")
    @Builder.Default
    private String tokenType = "Bearer";
}
