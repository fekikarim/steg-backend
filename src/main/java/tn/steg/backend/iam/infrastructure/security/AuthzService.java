package tn.steg.backend.iam.infrastructure.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tn.steg.backend.common.domain.model.UserPrincipal;

import java.util.UUID;

/**
 * Authorization evaluator bean exposed as {@code @authz} for SpEL expressions in {@code @PreAuthorize}.
 *
 * Example usages:
 * <pre>
 *   {@code @PreAuthorize("@authz.hasRole('FINANCE')")}
 *   {@code @PreAuthorize("@authz.isCurrentUser(#userId)")}
 *   {@code @PreAuthorize("@authz.isOwnConversation(#conversationId)")}
 * </pre>
 */
@Slf4j
@Component("authz")
@RequiredArgsConstructor
public class AuthzService {

    public boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }

    public boolean isCurrentUser(UUID userId) {
        if (userId == null) {
            return false;
        }
        UUID currentId = getCurrentUserId();
        return userId.equals(currentId);
    }

    public boolean hasRole(String role) {
        if (role == null) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        String target = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase(target));
    }

    public boolean hasAnyRole(String... roles) {
        if (roles == null || roles.length == 0) {
            return false;
        }
        for (String role : roles) {
            if (hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    public UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getId();
        }
        return null;
    }

    public String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            if (auth.getPrincipal() instanceof UserPrincipal principal) {
                return principal.getEmail();
            } else if (auth.getPrincipal() instanceof String email) {
                return email;
            }
        }
        return null;
    }

    public boolean isOwnConversation(UUID conversationId) {
        // Conversation ownership hook: will be integrated with ConversationRepository in Phase B
        return isAuthenticated();
    }
}
