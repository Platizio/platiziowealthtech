package com.platizio.wealthtech.repository;

import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.ProductCategory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductSchemeRepository extends JpaRepository<ProductScheme, UUID> {
    List<ProductScheme> findByAmcNameContainingIgnoreCaseAndCategory(String amcName, ProductCategory category);
    Optional<ProductScheme> findByExternalSchemeCode(String externalSchemeCode);
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
               AND p.externalSchemeCode NOT IN :codes
               AND LENGTH(TRIM(p.externalSchemeCode)) > 0
            """)
    int deactivateActiveSchemesNotIn(@Param("codes") Collection<String> codes);

    Optional<ProductScheme> findFirstByExternalSchemeCodeIgnoreCase(String externalSchemeCode);
    Optional<ProductScheme> findFirstByExternalIsinIgnoreCase(String externalIsin);
    Optional<ProductScheme> findFirstBySchemeNameIgnoreCase(String schemeName);
}
