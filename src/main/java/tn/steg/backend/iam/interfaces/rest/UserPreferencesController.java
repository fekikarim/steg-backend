package tn.steg.backend.iam.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;

import java.util.Set;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
@Tag(name = "User Preferences", description = "Current user preferences (locale, notifications)")
public class UserPreferencesController {

    private final UserRepository userRepository;

    private static final Set<String> SUPPORTED = Set.of("fr", "en", "ar");

    @PutMapping("/locale")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update preferred locale for current user",
            description = "Persists fr/en/ar for email notifications and future sessions. Backend also respects Accept-Language header per-request for validation messages.")
    public ResponseEntity<Void> updateLocale(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody LocaleRequest request) {
        String locale = request.getLocale() != null ? request.getLocale().trim().toLowerCase() : "fr";
        if (!SUPPORTED.contains(locale)) {
            locale = "fr";
        }
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setPreferredLocale(locale);
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class LocaleRequest {
        @Pattern(regexp = "^(fr|en|ar)$", message = "Locale must be fr, en, or ar")
        private String locale;
    }
}
