package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.OnboardingSubmission;
import com.platizio.wealthtech.domain.OnboardingSubmissionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingSubmissionRepository extends JpaRepository<OnboardingSubmission, UUID> {

    /** The current live (non-superseded) revision for the investor, if any. */
    Optional<OnboardingSubmission> findFirstByInvestorIdAndStatusNotOrderByRevisionNoDesc(
            UUID investorId, OnboardingSubmissionStatus status);

    /** The highest revision number ever issued for the investor (for numbering). */
    Optional<OnboardingSubmission> findFirstByInvestorIdOrderByRevisionNoDesc(UUID investorId);
}
