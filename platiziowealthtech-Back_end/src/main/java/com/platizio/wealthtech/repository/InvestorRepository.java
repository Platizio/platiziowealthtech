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
    List<Investor> findByDistributorId(UUID distributorId);
    List<Investor> findByDistributorIdIn(List<UUID> distributorIds);
    List<Investor> findByPostalCode(String postalCode);
    List<Investor> findByKycStatus(KycStatus kycStatus);
    List<Investor> findByDistributorIdAndKycStatus(UUID distributorId, KycStatus kycStatus);

    @Query("""
            select i from Investor i
            where lower(i.fullName) like lower(concat('%', :query, '%'))
               or lower(i.email) like lower(concat('%', :query, '%'))
               or lower(i.mobileNumber) like lower(concat('%', :query, '%'))
               or lower(i.pan) like lower(concat('%', :query, '%'))
            order by i.fullName
            """)
    List<Investor> search(@Param("query") String query, Pageable pageable);

    @Query("""
            select i from Investor i
            where i.distributorId = :distributorId
              and (
                    lower(i.fullName) like lower(concat('%', :query, '%'))
                 or lower(i.email) like lower(concat('%', :query, '%'))
                 or lower(i.mobileNumber) like lower(concat('%', :query, '%'))
                 or lower(i.pan) like lower(concat('%', :query, '%'))
              )
            order by i.fullName
            """)
    List<Investor> searchByDistributor(
            @Param("distributorId") UUID distributorId,
            @Param("query") String query,
            Pageable pageable
    );

    @Query("""
            select i from Investor i
            where i.distributorId in :distributorIds
              and (
                    lower(i.fullName) like lower(concat('%', :query, '%'))
                 or lower(i.email) like lower(concat('%', :query, '%'))
                 or lower(i.mobileNumber) like lower(concat('%', :query, '%'))
                 or lower(i.pan) like lower(concat('%', :query, '%'))
              )
            order by i.fullName
            """)
    List<Investor> searchByDistributorIds(
            @Param("distributorIds") List<UUID> distributorIds,
            @Param("query") String query,
            Pageable pageable
    );
}
