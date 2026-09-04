package tn.steg.backend.iam.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tn.steg.backend.iam.application.dto.TokenPayload;
import tn.steg.backend.iam.application.port.out.TokenServicePort;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class JwtService implements TokenServicePort {

    private final SecretKey signingKey;
    private final String issuer;
    private final long accessTokenExpirationMinutes;
    private final long refreshTokenExpirationDays;

    public JwtService(
            @Value("${steg.security.jwt.secret-key}") String secretKey,
            @Value("${steg.security.jwt.issuer}") String issuer,
            @Value("${steg.security.jwt.access-token-expiration-minutes}") long accessTokenExpirationMinutes,
            @Value("${steg.security.jwt.refresh-token-expiration-days}") long refreshTokenExpirationDays) {
        this.signingKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.accessTokenExpirationMinutes = accessTokenExpirationMinutes;
        this.refreshTokenExpirationDays = refreshTokenExpirationDays;
    }

    public String generateAccessToken(UUID userId, String email, List<String> roles) {
        Instant now = Instant.now();
        Instant expiration = now.plus(accessTokenExpirationMinutes, ChronoUnit.MINUTES);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .claim("email", email)
                .claim("roles", roles)
                .signWith(signingKey)
                .compact();
    }

    public String generateRefreshToken() {
        byte[] randomBytes = new byte[64];
        new SecureRandom().nextBytes(randomBytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenExpirationDays * 24 * 60 * 60;
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMinutes * 60;
    }

    public Claims validateAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String hashToken(String rawToken) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public TokenPayload extractPayload(Claims claims) {
        return TokenPayload.builder()
                .userId(UUID.fromString(claims.getSubject()))
                .email(claims.get("email", String.class))
                .roles(claims.get("roles", List.class))
                .issuedAt(claims.getIssuedAt().toInstant())
                .expiresAt(claims.getExpiration().toInstant())
                .jti(claims.getId())
                .build();
    }
}
