package tn.steg.backend.iam.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Refresh token request")
public class RefreshRequest {

    @NotBlank(message = "Refresh token is required")
    @Schema(description = "The refresh token obtained during login", example = "eyJhbGciOiJIUz...")
    private String refreshToken;
}
