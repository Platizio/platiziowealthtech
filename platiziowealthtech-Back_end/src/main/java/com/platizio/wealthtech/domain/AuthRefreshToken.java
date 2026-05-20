package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class AuthRefreshToken extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private UUID distributorId;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    private Boolean revoked = Boolean.FALSE;

    private OffsetDateTime revokedAt;

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public UUID getDistributorId() {
        return distributorId;
    }

    public void setDistributorId(UUID distributorId) {
        this.distributorId = distributorId;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Boolean getRevoked() {
        return revoked;
    }

    public void setRevoked(Boolean revoked) {
        this.revoked = revoked;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}
