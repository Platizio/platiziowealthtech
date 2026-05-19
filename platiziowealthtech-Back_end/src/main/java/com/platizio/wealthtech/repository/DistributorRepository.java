package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DistributorRepository extends JpaRepository<Distributor, UUID> {
    Optional<Distributor> findByEmail(String email);
    boolean existsByArnNumber(String arnNumber);
    @Query("select count(d) > 0 from Distributor d where d.eUinNumber = :eUinNumber")
    boolean existsByEUinNumber(@Param("eUinNumber") String eUinNumber);
    List<Distributor> findByMasterDistributorId(UUID masterDistributorId);
    List<Distributor> findByRole(DistributorRole role);

    @Query(value = """
            select *
            from distributors
            where full_name ilike concat('%', :query, '%')
               or email ilike concat('%', :query, '%')
               or mobile_number ilike concat('%', :query, '%')
               or arn_number ilike concat('%', :query, '%')
               or e_uin_number ilike concat('%', :query, '%')
            order by full_name
            """, nativeQuery = true)
    List<Distributor> search(@Param("query") String query, Pageable pageable);

    @Query(value = """
            select *
            from distributors
            where master_distributor_id = :masterDistributorId
              and (
                    full_name ilike concat('%', :query, '%')
                 or email ilike concat('%', :query, '%')
                 or mobile_number ilike concat('%', :query, '%')
                 or arn_number ilike concat('%', :query, '%')
                 or e_uin_number ilike concat('%', :query, '%')
              )
            order by full_name
            """, nativeQuery = true)
    List<Distributor> searchByMasterDistributor(
            @Param("masterDistributorId") UUID masterDistributorId,
            @Param("query") String query,
            Pageable pageable
    );
}
