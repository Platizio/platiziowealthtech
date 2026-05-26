package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.BankVerificationStatus;
import com.platizio.wealthtech.domain.KycStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestorRepository extends JpaRepository<Investor, UUID> {
    Optional<Investor> findByPan(String pan);
    Optional<Investor> findByEmail(String email);
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
