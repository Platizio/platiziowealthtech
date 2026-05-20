package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.KycStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestorRepository extends JpaRepository<Investor, UUID> {
    Optional<Investor> findByPan(String pan);
    Optional<Investor> findByEmail(String email);
    List<Investor> findByDistributorId(UUID distributorId);
    List<Investor> findByDistributorIdIn(List<UUID> distributorIds);
    List<Investor> findByPostalCode(String postalCode);
    List<Investor> findByKycStatus(KycStatus kycStatus);
    List<Investor> findByDistributorIdAndKycStatus(UUID distributorId, KycStatus kycStatus);

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
