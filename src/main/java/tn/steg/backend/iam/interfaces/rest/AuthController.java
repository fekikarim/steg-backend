package tn.steg.backend.iam.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.common.interfaces.rest.PublicEndpoint;
import tn.steg.backend.iam.application.AuthService;
import tn.steg.backend.iam.application.dto.AuthResponse;
import tn.steg.backend.iam.application.dto.LoginRequest;
import tn.steg.backend.iam.application.dto.RefreshRequest;
import tn.steg.backend.iam.application.dto.RegisterRequest;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, login, token refresh, and logout endpoints")
public class AuthController {

    private final AuthService authService;

    @PublicEndpoint
    @PostMapping("/register")
    @Operation(summary = "Register a new candidate account", description = "Self-registration for candidates only. Staff accounts are provisioned by administrators.")
    @SecurityRequirements
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        AuthResponse response = authService.register(request, extractIpAddress(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PublicEndpoint
    @PostMapping("/login")
    @Operation(summary = "Authenticate and obtain JWT tokens", description = "Returns access token and refresh token on successful authentication.")
    @SecurityRequirements
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        AuthResponse response = authService.login(
                request,
                extractIpAddress(httpRequest),
                httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(response);
    }

    @PublicEndpoint
    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token", description = "Exchange a valid refresh token for a new access token and refresh token pair. Old refresh token is revoked.")
    @SecurityRequirements
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                HttpServletRequest httpRequest) {
        AuthResponse response = authService.refresh(
                request,
                extractIpAddress(httpRequest),
                httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout")
    @Operation(summary = "Logout and revoke current session", description = "Revokes the refresh token and user session.")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        UUID userId = getCurrentUserId();
        if (userId != null) {
            authService.logout(request.getRefreshToken(), userId);
        }
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout-all")
    @Operation(summary = "Logout from all devices", description = "Revokes all refresh tokens and sessions for the current user.")
    public ResponseEntity<Void> logoutAll() {
        UUID userId = getCurrentUserId();
        if (userId != null) {
            authService.logoutAll(userId);
        }
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            if (auth.getPrincipal() instanceof UserPrincipal principal) {
                return principal.getId();
            } else if (auth.getPrincipal() instanceof String email) {
                return authService.getUserIdByEmail(email);
            }
        }
        return null;
    }

    private String extractIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
