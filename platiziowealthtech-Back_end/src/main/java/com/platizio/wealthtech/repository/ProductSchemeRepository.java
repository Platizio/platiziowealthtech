package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.ProductCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSchemeRepository extends JpaRepository<ProductScheme, UUID> {
    List<ProductScheme> findByAmcNameContainingIgnoreCaseAndCategory(String amcName, ProductCategory category);
    Optional<ProductScheme> findByExternalSchemeCode(String externalSchemeCode);
    Optional<ProductScheme> findFirstByExternalSchemeCodeIgnoreCase(String externalSchemeCode);
    Optional<ProductScheme> findFirstByExternalIsinIgnoreCase(String externalIsin);
    Optional<ProductScheme> findFirstBySchemeNameIgnoreCase(String schemeName);
}
