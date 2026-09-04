package tn.steg.backend.iam.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Candidate self-registration request")
public class RegisterRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Schema(description = "User email address", example = "candidate@university.tn")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
    @Schema(description = "Password (min 8 characters)", example = "********")
    private String password;

    @NotBlank(message = "First name is required")
    @Size(max = 100)
    @Schema(description = "First name", example = "Ahmed")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    @Schema(description = "Last name", example = "Ben Ali")
    private String lastName;

    @Size(max = 20)
    @Schema(description = "Phone number", example = "+216 71 123 456")
    private String phone;
}
