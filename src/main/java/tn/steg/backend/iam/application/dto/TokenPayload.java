package tn.steg.backend.iam.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Decoded JWT token payload")
public class TokenPayload {

    @Schema(description = "User ID")
    private UUID userId;

    @Schema(description = "User email")
    private String email;

    @Schema(description = "Assigned role codes")
    private List<String> roles;

    @Schema(description = "Token issued at")
    private Instant issuedAt;

    @Schema(description = "Token expiration")
    private Instant expiresAt;

    @Schema(description = "Unique token identifier")
    private String jti;
}
