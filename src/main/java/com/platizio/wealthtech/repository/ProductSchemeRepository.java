package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.ProductScheme;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSchemeRepository extends JpaRepository<ProductScheme, UUID> {
    List<ProductScheme> findByAmcNameContainingIgnoreCaseAndCategoryContainingIgnoreCase(String amcName, String category);
}