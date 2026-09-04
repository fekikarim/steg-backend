package tn.steg.backend.iam.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.iam.application.dto.AuthResponse;
import tn.steg.backend.iam.application.dto.LoginRequest;
import tn.steg.backend.iam.application.dto.RefreshRequest;
import tn.steg.backend.iam.application.dto.RegisterRequest;
import tn.steg.backend.iam.domain.exception.AccountLockedException;
import tn.steg.backend.iam.domain.exception.AuthenticationFailedException;
import tn.steg.backend.iam.domain.exception.InvalidRefreshTokenException;
import tn.steg.backend.iam.domain.model.RefreshToken;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserSession;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RefreshTokenRepository;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.iam.domain.repository.UserSessionRepository;
import tn.steg.backend.iam.application.port.out.TokenServicePort;
import tn.steg.backend.iam.application.port.out.RateLimiterPort;
import tn.steg.backend.audit.application.AuditService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenServicePort jwtService;
    private final RateLimiterPort rateLimiter;
    private final AuditService auditService;

    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AuthenticationFailedException("An account with this email already exists.");
        }

        Role candidateRole = roleRepository.findByCode("CANDIDATE")
                .orElseThrow(() -> new IllegalStateException("CANDIDATE role not found. Run V15__seed_rbac.sql migration."));

        User user = new User(request.getEmail(), passwordEncoder.encode(request.getPassword()), UserStatus.ACTIVE);
        user.setEnabled(true);
        user.setFailedLoginAttempts(0);
        user.setPreferredLocale("fr");
        user.setAssignedRoles(Set.of(candidateRole));

        user = userRepository.save(user);

        log.info("New candidate registered: {}", user.getEmail());

        auditService.log("USER_REGISTERED", "User", user.getId(),
                null, user.getEmail(), user.getId(), ipAddress);

        return issueTokens(user, ipAddress, "Registration");
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String rateLimitKey = "login:" + ipAddress;
        if (rateLimiter.isRateLimited(rateLimitKey)) {
            throw new AuthenticationFailedException("Too many login attempts. Please try again later.");
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(AuthenticationFailedException::invalidCredentials);

        if (user.getStatus() == UserStatus.LOCKED) {
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
                throw new AccountLockedException(user.getLockedUntil());
            }
            user.setStatus(UserStatus.ACTIVE);
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            handleFailedLogin(user, ipAddress);
            throw AuthenticationFailedException.invalidCredentials();
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        rateLimiter.reset(rateLimitKey);

        log.info("User logged in: {}", user.getEmail());

        auditService.log("USER_LOGIN", "User", user.getId(),
                null, user.getEmail(), user.getId(), ipAddress);

        return issueTokens(user, ipAddress, userAgent);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request, String ipAddress, String userAgent) {
        String tokenHash = jwtService.hashToken(request.getRefreshToken());

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::notFound);

        if (refreshToken.getRevokedAt() != null) {
            throw InvalidRefreshTokenException.revoked();
        }

        if (refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw InvalidRefreshTokenException.expired();
        }

        refreshToken.setRevokedAt(Instant.now());
        refreshTokenRepository.save(refreshToken);

        User user = refreshToken.getUser();

        if (user.getStatus() == UserStatus.LOCKED) {
            throw new AccountLockedException(user.getLockedUntil());
        }

        log.info("Refresh token rotated for user: {}", user.getEmail());

        return issueTokens(user, ipAddress, userAgent);
    }

    @Transactional
    public void logout(String refreshTokenValue, UUID userId) {
        if (refreshTokenValue != null) {
            String tokenHash = jwtService.hashToken(refreshTokenValue);
            refreshTokenRepository.findByTokenHash(tokenHash)
                    .ifPresent(token -> {
                        token.setRevokedAt(Instant.now());
                        refreshTokenRepository.save(token);
                    });
        }

        userSessionRepository.findByUserIdAndRevokedAtIsNull(userId)
                .forEach(session -> {
                    session.setRevokedAt(Instant.now());
                    userSessionRepository.save(session);
                });

        log.info("User logged out: userId={}", userId);
    }

    @Transactional
    public void logoutAll(UUID userId) {
        refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)
                .forEach(token -> {
                    token.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(token);
                });

        userSessionRepository.findByUserIdAndRevokedAtIsNull(userId)
                .forEach(session -> {
                    session.setRevokedAt(Instant.now());
                    userSessionRepository.save(session);
                });

        log.info("All sessions revoked for user: userId={}", userId);
    }

    public UUID getUserIdByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(User::getId)
                .orElse(null);
    }

    private AuthResponse issueTokens(User user, String ipAddress, String userAgent) {
        List<String> roleCodes = user.getAssignedRoles().stream()
                .map(role -> "ROLE_" + role.getCode())
                .toList();

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), roleCodes);
        String refreshTokenRaw = jwtService.generateRefreshToken();
        String refreshTokenHash = jwtService.hashToken(refreshTokenRaw);

        Instant refreshExpiresAt = Instant.now().plus(
                jwtService.getRefreshTokenExpirationSeconds(), ChronoUnit.SECONDS);

        RefreshToken refreshToken = new RefreshToken(user, refreshTokenHash, refreshExpiresAt);
        refreshTokenRepository.save(refreshToken);

        UserSession session = new UserSession(user,
                ipAddress != null ? ipAddress : "unknown",
                userAgent != null ? userAgent : "unknown",
                refreshExpiresAt);
        userSessionRepository.save(session);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenRaw)
                .expiresIn(jwtService.getAccessTokenExpirationSeconds())
                .tokenType("Bearer")
                .build();
    }

    private void handleFailedLogin(User user, String ipAddress) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        if (attempts >= 5) {
            Instant lockoutUntil = Instant.now().plus(30, ChronoUnit.MINUTES);
            user.setStatus(UserStatus.LOCKED);
            user.setLockedUntil(lockoutUntil);
            log.warn("Account locked for user {} after {} failed attempts. Locked until: {}",
                    user.getEmail(), attempts, lockoutUntil);

            auditService.log("ACCOUNT_LOCKED", "User", user.getId(),
                    null, "Locked after " + attempts + " failed attempts", user.getId(), ipAddress);
        }

        userRepository.save(user);
    }
}
