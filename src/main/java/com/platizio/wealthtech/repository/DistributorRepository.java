package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.Distributor;
import com.platizio.wealthtech.domain.DistributorRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
}
