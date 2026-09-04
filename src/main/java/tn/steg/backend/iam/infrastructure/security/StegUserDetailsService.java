package tn.steg.backend.iam.infrastructure.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.UserRepository;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StegUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.debug("User not found with email: {}", email);
                    return new UsernameNotFoundException("No account found with email: " + email);
                });

        boolean accountNonLocked = true;
        boolean accountNonExpired = true;
        boolean credentialsNonExpired = true;
        boolean enabled = user.getEnabled() && user.getStatus() == UserStatus.ACTIVE;

        if (user.getStatus() == UserStatus.LOCKED) {
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
                accountNonLocked = false;
            } else {
                accountNonLocked = true;
            }
        } else if (user.getStatus() == UserStatus.INACTIVE) {
            enabled = false;
        }

        List<SimpleGrantedAuthority> authorities = user.getAssignedRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getCode()))
                .toList();

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPasswordHash(),
                enabled,
                accountNonExpired,
                credentialsNonExpired,
                accountNonLocked,
                authorities
        );
    }
}
