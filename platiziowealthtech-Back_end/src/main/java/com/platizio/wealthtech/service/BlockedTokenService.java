package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.BlockedToken;
import com.platizio.wealthtech.repository.BlockedTokenRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockedTokenService {

    private final BlockedTokenRepository blockedTokenRepository;

    public BlockedTokenService(BlockedTokenRepository blockedTokenRepository) {
        this.blockedTokenRepository = blockedTokenRepository;
    }

    public boolean isBlocked(String jti) {
        return jti != null && blockedTokenRepository.existsById(jti);
    }

    @Transactional
    public void block(String jti, OffsetDateTime expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null || expiresAt.isBefore(OffsetDateTime.now())) {
            return;
        }
        BlockedToken blockedToken = new BlockedToken();
        blockedToken.setJti(jti);
        blockedToken.setExpiresAt(expiresAt);
        blockedTokenRepository.save(blockedToken);
    }

    @Transactional
    public long purgeExpired() {
        return blockedTokenRepository.deleteByExpiresAtBefore(OffsetDateTime.now());
    }
}
