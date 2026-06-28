package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.KycStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestorRepository extends JpaRepository<Investor, UUID> {
    Optional<Investor> findByPan(String pan);

    /**
     * SEC-3: pessimistic-write load used to serialize concurrent "Send to Investor"
     * calls for the same investor. The partial-unique index already blocks two live
     * PENDING link rows; taking the row lock at the start of {@code sendToInvestor}
     * makes concurrent sends queue gracefully instead of racing to a constraint error.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Investor i where i.id = :id")
    Optional<Investor> findForUpdateById(@Param("id") UUID id);

    /** R5: investors awaiting a given distributor's onboarding approval (distributor_id not yet linked). */
    List<Investor> findByPendingDistributorId(UUID pendingDistributorId);

    @Query(value = "select * from investors where pan = :pan order by updated_at desc limit 1", nativeQuery = true)
    Optional<Investor> findIncludingDeletedByPan(@Param("pan") String pan);

    @Query(value = """
            select *
            from investors
            where pan = :pan
              and distributor_id = :distributorId
            order by updated_at desc
            limit 1
            """, nativeQuery = true)
    Optional<Investor> findIncludingDeletedByPanAndDistributor(
            @Param("pan") String pan,
            @Param("distributorId") UUID distributorId
    );

    @Query(value = "select * from investors where cybrilla_investor_id = :profileId order by updated_at desc limit 1", nativeQuery = true)
    Optional<Investor> findIncludingDeletedByCybrillaInvestorId(@Param("profileId") String profileId);

    // BUG-031: @SQLRestriction hides soft-deleted rows from findByPan/findByEmail, but the
    // pan/email unique constraints are NOT partial, so an INSERT still collides with a
    // soft-deleted row. These native lookups bypass the restriction to detect that collision
    // and surface a friendly DuplicateResourceException instead of a raw DB integrity error.
    @Query(value = "SELECT id FROM investors WHERE pan = :pan LIMIT 1", nativeQuery = true)
    Optional<UUID> findAnyIdByPanIncludingDeleted(@Param("pan") String pan);

    @Query(value = "SELECT id FROM investors WHERE email = :email LIMIT 1", nativeQuery = true)
    Optional<UUID> findAnyIdByEmailIncludingDeleted(@Param("email") String email);

    @Query(value = """
            select count(*)
            from investors
            where distributor_id = :distributorId
              and is_deleted = true
            """, nativeQuery = true)
    long countSoftDeletedByDistributorId(@Param("distributorId") UUID distributorId);
    Optional<Investor> findByEmail(String email);
    Optional<Investor> findByExternalKycCheckId(String externalKycCheckId);
    Optional<Investor> findByExternalKycRequestId(String externalKycRequestId);
    Optional<Investor> findByExternalIdentityDocumentId(String externalIdentityDocumentId);
    Optional<Investor> findByExternalEsignId(String externalEsignId);
    List<Investor> findByDistributorId(UUID distributorId);
    List<Investor> findByDistributorIdIn(List<UUID> distributorIds);
    List<Investor> findByHouseholdIdAndDistributorId(UUID householdId, UUID distributorId);
    boolean existsByHouseholdIdAndDistributorId(UUID householdId, UUID distributorId);
    Page<Investor> findByDistributorId(UUID distributorId, Pageable pageable);
    Page<Investor> findByDistributorIdIn(List<UUID> distributorIds, Pageable pageable);
    List<Investor> findByDistributorIdAndKycStatusNot(UUID distributorId, KycStatus kycStatus, Pageable pageable);
    List<Investor> findByDistributorIdAndBankVerificationStatusNot(
            UUID distributorId,
            BankVerificationStatus bankVerificationStatus,
            Pageable pageable);
    List<Investor> findByDistributorIdAndKycStatusAndBankVerificationStatusNot(
            UUID distributorId,
            KycStatus kycStatus,
            BankVerificationStatus bankVerificationStatus,
            Pageable pageable);
    List<Investor> findByDistributorIdAndKycStatusAndBankVerificationStatus(
            UUID distributorId,
            KycStatus kycStatus,
            BankVerificationStatus bankVerificationStatus,
            Pageable pageable);
    List<Investor> findByPostalCode(String postalCode);
    List<Investor> findByKycStatus(KycStatus kycStatus);
    List<Investor> findByDistributorIdAndKycStatus(UUID distributorId, KycStatus kycStatus);

    @Query("""
            select i
            from Investor i
            where i.kycStatus in :statuses
              and (
                    i.externalKycCheckId is not null
                 or i.externalKycRequestId is not null
                 or i.externalIdentityDocumentId is not null
                 or i.externalEsignId is not null
              )
              and (i.externalKycCheckId is null or i.externalKycCheckId not like 'pv_demo_%')
              and (i.externalKycRequestId is null or i.externalKycRequestId not like 'kycr_demo_%')
            order by i.updatedAt asc
            """)
    List<Investor> findKycSyncCandidates(@Param("statuses") List<KycStatus> statuses, Pageable pageable);

    @Query("""
            select i
            from Investor i
            where i.dateOfBirth is not null
               or i.anniversaryDate is not null
               or i.goalMaturityDate is not null
            """)
    Page<Investor> findInvestorsWithLifeEventDates(Pageable pageable);

    @Query(value = """
            select *
            from investors
            where is_deleted = false
              and (
                    full_name ilike concat('%', :query, '%')
                 or email ilike concat('%', :query, '%')
                 or mobile_number ilike concat('%', :query, '%')
                 or pan ilike concat('%', :query, '%')
              )
            order by full_name
            """, nativeQuery = true)
    List<Investor> search(@Param("query") String query, Pageable pageable);

    @Query(value = """
            select *
            from investors
            where is_deleted = false
              and distributor_id = :distributorId
              and (
                    full_name ilike concat('%', :query, '%')
                 or email ilike concat('%', :query, '%')
                 or mobile_number ilike concat('%', :query, '%')
                 or pan ilike concat('%', :query, '%')
              )
            order by full_name
            """, nativeQuery = true)
    List<Investor> searchByDistributor(
            @Param("distributorId") UUID distributorId,
            @Param("query") String query,
            Pageable pageable
    );

    @Query(value = """
            select *
            from investors
            where is_deleted = false
              and distributor_id in (:distributorIds)
              and (
                    full_name ilike concat('%', :query, '%')
                 or email ilike concat('%', :query, '%')
                 or mobile_number ilike concat('%', :query, '%')
                 or pan ilike concat('%', :query, '%')
              )
            order by full_name
            """, nativeQuery = true)
    List<Investor> searchByDistributorIds(
            @Param("distributorIds") List<UUID> distributorIds,
            @Param("query") String query,
            Pageable pageable
    );
}
