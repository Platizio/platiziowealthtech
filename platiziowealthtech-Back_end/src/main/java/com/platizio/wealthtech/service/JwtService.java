package com.platizio.wealthtech.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms}")
    private long expirationMs;

    @Value("${app.auth.investor-access-expiration-ms:3600000}")
    private long investorExpirationMs;

    /** Token actor-type discriminator (claim {@code typ}). */
    public static final String TYPE_DISTRIBUTOR = "DISTRIBUTOR";
    public static final String TYPE_INVESTOR = "INVESTOR";

    @PostConstruct
    void validateSecret() {
        Assert.hasText(secret, "JWT_SECRET must be set");
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(UUID distributorId, String email, String role) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(distributorId.toString())
                .claim("email", email)
                .claim("role", role)
                .claim("typ", TYPE_DISTRIBUTOR)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    /** Investor-portal access token (passwordless). Distinct {@code typ} so the
     * filter builds an investor principal that can never act as a distributor. */
    public String generateInvestorToken(UUID investorAccountId, String email) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(investorAccountId.toString())
                .claim("email", email)
                .claim("role", "INVESTOR")
                .claim("typ", TYPE_INVESTOR)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + investorExpirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    /** Actor type of a token ({@code DISTRIBUTOR} default for legacy tokens without the claim). */
    public String extractType(String token) {
        String typ = extractAllClaims(token).get("typ", String.class);
        return typ == null ? TYPE_DISTRIBUTOR : typ;
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractSubject(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractEmail(String token) {
        return extractAllClaims(token).get("email", String.class);
    }

    public String extractJti(String token) {
        return extractAllClaims(token).getId();
    }

    public OffsetDateTime extractExpiresAt(String token) {
        return extractAllClaims(token).getExpiration().toInstant().atOffset(ZoneOffset.UTC);
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }
}
