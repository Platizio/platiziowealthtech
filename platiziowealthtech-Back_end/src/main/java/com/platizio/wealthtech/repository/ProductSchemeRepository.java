package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.ProductCategory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductSchemeRepository extends JpaRepository<ProductScheme, UUID> {
    List<ProductScheme> findByAmcNameContainingIgnoreCaseAndCategory(String amcName, ProductCategory category);
    Page<ProductScheme> findByActiveTrue(Pageable pageable);
    Optional<ProductScheme> findByExternalSchemeCode(String externalSchemeCode);

    @Query("""
            SELECT p
              FROM ProductScheme p
             WHERE (:queryPattern IS NULL
                    OR LOWER(p.schemeName) LIKE :queryPattern
                    OR LOWER(p.amcName) LIKE :queryPattern
                    OR LOWER(p.externalSchemeCode) LIKE :queryPattern
                    OR (p.externalIsin IS NOT NULL
                        AND LOWER(CAST(p.externalIsin AS string)) LIKE :queryPattern))
               AND (:active IS NULL OR p.active = :active)
               AND (:category IS NULL OR p.category = :category)
               AND (:productType IS NULL
                    OR (p.productType IS NOT NULL AND LOWER(p.productType) = :productType))
               AND (:assetClass IS NULL
                    OR (:assetClass = 'SIF'
                        AND (p.category = com.platizio.wealthtech.domain.ProductCategory.SIF
                             OR (p.productType IS NOT NULL AND LOWER(p.productType) LIKE '%sif%')
                             OR LOWER(p.schemeName) LIKE '%sif%'))
                    OR (:assetClass = 'MF'
                        AND p.category <> com.platizio.wealthtech.domain.ProductCategory.SIF
                        AND (p.productType IS NULL OR LOWER(p.productType) NOT LIKE '%sif%')
                        AND LOWER(p.schemeName) NOT LIKE '%sif%'))
            """)
    Page<ProductScheme> searchSchemes(
            @Param("queryPattern") String queryPattern,
            @Param("active") Boolean active,
            @Param("assetClass") String assetClass,
            @Param("category") ProductCategory category,
            @Param("productType") String productType,
            Pageable pageable
    );

    /**
     * B-69: bulk-deactivate active schemes whose external_scheme_code is NOT
     * in the supplied refreshed-set. Replaces a previous
     * findAll().stream().filter(...).saveAll() pattern that pulled every row
     * (including the metadataJson blob) into the JVM on every refresh.
     *
     * Filters preserved from the original in-memory pipeline:
     *   • `p.active = true` — leave already-inactive rows untouched.
     *   • `p.externalSchemeCode NOT IN :codes` — only deactivate rows not in
     *     the canonical set. NULL external codes are naturally excluded by
     *     SQL three-valued logic on NOT IN.
     *   • `LENGTH(TRIM(p.externalSchemeCode)) > 0` — defensive against legacy
     *     empty/whitespace-only codes (matches the original StringUtils.hasText
     *     filter; without this, '' NOT IN (...) is TRUE and would deactivate
     *     malformed rows).
     *
     * flushAutomatically=true flushes the persistence context first so any
     * upserts performed earlier in the same transaction are visible to the
     * UPDATE. clearAutomatically=true evicts cached entities afterwards so
     * subsequent reads in the same transaction don't see stale active=true.
     *
     * @return number of rows updated
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ProductScheme p
               SET p.active = false
             WHERE p.active = true
               AND p.category IN :categories
               AND p.externalSchemeCode NOT IN :codes
               AND LENGTH(TRIM(p.externalSchemeCode)) > 0
            """)
    int deactivateActiveSchemesNotIn(
            @Param("codes") Collection<String> codes,
            @Param("categories") Collection<ProductCategory> categories
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ProductScheme p WHERE p.category IN :categories")
    int deleteSchemesByCategoryIn(@Param("categories") Collection<ProductCategory> categories);

    /** Removes demo/seed rows that were never imported from Finprim (no fetch snapshot persisted). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ProductScheme p WHERE p.externalFetchRequestJson IS NULL")
    int deleteByExternalFetchRequestJsonIsNull();

    long countByExternalFetchRequestJsonIsNotNull();

    Optional<ProductScheme> findFirstByExternalSchemeCodeIgnoreCase(String externalSchemeCode);
    Optional<ProductScheme> findFirstByExternalIsinIgnoreCase(String externalIsin);
    Optional<ProductScheme> findFirstBySchemeNameIgnoreCase(String schemeName);
}
