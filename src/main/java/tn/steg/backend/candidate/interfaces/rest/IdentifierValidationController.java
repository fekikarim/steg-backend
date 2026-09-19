package tn.steg.backend.candidate.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.candidate.application.IdentifierValidationService;
import tn.steg.backend.candidate.application.dto.IdentifierValidationRequest;
import tn.steg.backend.candidate.application.dto.IdentifierValidationResponse;
import tn.steg.backend.common.domain.annotation.RateLimited;

@RestController
@RequestMapping("/api/public/applications")
@RequiredArgsConstructor
@Tag(name = "Applications", description = "Public application identifier validation")
public class IdentifierValidationController {

    private final IdentifierValidationService validationService;

    @PostMapping("/validate-identifiers")
    @RateLimited(name = "identifier-validation", limit = 10, windowSeconds = 60)
    @Operation(
            summary = "Validate if email, CIN or phone already has an existing application",
            description = "Checks whether any of the provided identifiers (email, nationalId/CIN, phone) is already associated with a candidate who has an existing application. "
                    + "Returns which fields are duplicates without exposing candidate details. "
                    + "If authenticated and the duplicate belongs to the current user, it is not considered a conflict. "
                    + "Rate-limited to 10 requests/minute."
    )
    public ResponseEntity<IdentifierValidationResponse> validateIdentifiers(
            @Valid @RequestBody IdentifierValidationRequest request) {
        // At least one identifier must be provided
        boolean hasAny = (request.email() != null && !request.email().isBlank())
                || (request.nationalId() != null && !request.nationalId().isBlank())
                || (request.phone() != null && !request.phone().isBlank());
        if (!hasAny) {
            return ResponseEntity.badRequest().body(
                    new IdentifierValidationResponse(false, java.util.List.of(), "At least one identifier (email, nationalId, or phone) must be provided.")
            );
        }
        IdentifierValidationResponse response = validationService.validate(request);
        return ResponseEntity.ok(response);
    }
}
