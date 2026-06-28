package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.EmailOtp;
import com.platizio.wealthtech.domain.OtpPurpose;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, UUID> {

    List<EmailOtp> findByEmailAndPurposeAndConsumedAtIsNull(String email, OtpPurpose purpose);

    Optional<EmailOtp> findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String email, OtpPurpose purpose);

    // Reference-scoped variants: bind a code to one subject (e.g. a transaction-approval
    // challenge) so concurrent challenges sharing (email, purpose) can't cross-consume.
    List<EmailOtp> findByEmailAndPurposeAndReferenceIdAndConsumedAtIsNull(
            String email, OtpPurpose purpose, UUID referenceId);

    Optional<EmailOtp> findFirstByEmailAndPurposeAndReferenceIdAndConsumedAtIsNullOrderByCreatedAtDesc(
            String email, OtpPurpose purpose, UUID referenceId);

    @Modifying
    @Query("delete from EmailOtp o where o.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
