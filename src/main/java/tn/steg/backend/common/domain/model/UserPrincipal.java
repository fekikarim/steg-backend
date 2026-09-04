package tn.steg.backend.common.domain.model;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Domain representation of an authenticated user principal extracted from JWT.
 * Held in Spring Security's SecurityContextHolder as the Authentication principal.
 */
public record UserPrincipal(
        UUID id,
        String email,
        List<String> roles
) implements Principal {

    @Override
    public String getName() {
        return email;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public List<String> getRoles() {
        return roles != null ? roles : List.of();
    }

    public boolean hasRole(String role) {
        String target = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return getRoles().stream()
                .anyMatch(r -> (r.startsWith("ROLE_") ? r : "ROLE_" + r).equalsIgnoreCase(target));
    }
}
