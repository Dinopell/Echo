package com.echo.controlplane.security;

import com.echo.controlplane.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验服务（HS256，本地单用户场景）
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String SUBJECT = "echo-local-user";

    private final JwtProperties jwtProperties;

    /**
     * 签发访问 Token
     */
    public String generateToken() {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getExpirationMs());

        return Jwts.builder()
                .subject(SUBJECT)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey())
                .compact();
    }

    /**
     * 校验 Token 并返回 subject
     */
    public String validateAndGetSubject(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long getExpirationMs() {
        return jwtProperties.getExpirationMs();
    }

    public boolean isApiKeyValid(String apiKey) {
        String configured = jwtProperties.getApiKey();
        return configured != null && !configured.isBlank()
                && configured.equals(apiKey);
    }

    private SecretKey signingKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
