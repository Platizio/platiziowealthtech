package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    private final ProductSchemeRepository productSchemeRepository;
    private final CybrillaClient cybrillaClient;

    public ProductService(ProductSchemeRepository productSchemeRepository, CybrillaClient cybrillaClient) {
        this.productSchemeRepository = productSchemeRepository;
        this.cybrillaClient = cybrillaClient;
    }

    public List<ProductScheme> listSchemes() {
        return productSchemeRepository.findAll();
    }

    public ProductScheme getScheme(java.util.UUID schemeId) {
        return productSchemeRepository.findById(schemeId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Scheme not found"));
    }

    @Transactional
    public List<ProductScheme> refreshFromCybrilla() {
        CybrillaClient.SchemeFetchResult fetched = cybrillaClient.fetchProductSchemes();
        List<ProductScheme> latest = dedupeByExternalSchemeCode(fetched.schemes());
        Set<String> refreshedSchemeCodes = externalSchemeCodes(latest);
        List<ProductScheme> merged = latest.stream()
                .map(this::mergeByExternalSchemeCode)
                .toList();
        List<ProductScheme> saved = productSchemeRepository.saveAll(merged);

        // B-68: only run the "deactivate schemes missing from this refresh"
        // pass when the upstream fetch was complete. A partial fetch (e.g.
        // truncated at the page cap) does NOT represent the full catalogue,
        // so subtracting from it would incorrectly deactivate every scheme
        // that simply landed beyond the truncation point. Upsert-only is
        // the safe behaviour for partials.
        if (fetched.complete()) {
            deactivateSchemesMissingFromLatestRefresh(refreshedSchemeCodes);
        } else {
            logger.warn(
                    "product_scheme_refresh status='deactivation_skipped' reason='partial_fetch' "
                            + "incomplete_reason='{}' fetched_count='{}'",
                    fetched.incompleteReason(), latest.size());
        }
        return saved;
    }

    @Transactional
    public void deleteScheme(java.util.UUID schemeId) {
        productSchemeRepository.deleteById(schemeId);
    }

    private List<ProductScheme> dedupeByExternalSchemeCode(List<ProductScheme> schemes) {
        Map<String, ProductScheme> uniqueSchemes = new LinkedHashMap<>();
        for (ProductScheme scheme : schemes) {
            String externalSchemeCode = scheme.getExternalSchemeCode();
            if (!StringUtils.hasText(externalSchemeCode)) {
                logger.warn("product_scheme_refresh status='skipped' reason='missing_external_scheme_code' scheme_name='{}'", scheme.getSchemeName());
                continue;
            }
            if (uniqueSchemes.containsKey(externalSchemeCode)) {
                logger.info("product_scheme_refresh status='duplicate_external_scheme_code' external_scheme_code='{}' action='last_record_wins'", externalSchemeCode);
            }
            uniqueSchemes.put(externalSchemeCode, scheme);
        }
        return List.copyOf(uniqueSchemes.values());
    }

    private ProductScheme mergeByExternalSchemeCode(ProductScheme incoming) {
        return productSchemeRepository.findByExternalSchemeCode(incoming.getExternalSchemeCode())
                .map(existing -> {
                    existing.setSchemeName(incoming.getSchemeName());
                    existing.setAmcName(incoming.getAmcName());
                    existing.setCategory(incoming.getCategory());
                    existing.setExternalIsin(incoming.getExternalIsin());
                    existing.setProductType(incoming.getProductType());
                    existing.setActive(incoming.getActive());
                    existing.setMetadataJson(incoming.getMetadataJson());
                    return existing;
                })
                .orElse(incoming);
    }

    private Set<String> externalSchemeCodes(List<ProductScheme> schemes) {
        Set<String> externalSchemeCodes = new HashSet<>();
        for (ProductScheme scheme : schemes) {
            if (StringUtils.hasText(scheme.getExternalSchemeCode())) {
                externalSchemeCodes.add(scheme.getExternalSchemeCode());
            }
        }
        return externalSchemeCodes;
    }

    private void deactivateSchemesMissingFromLatestRefresh(Set<String> refreshedSchemeCodes) {
        // The bulk UPDATE uses NOT IN :codes, which JPA providers treat as
        // ill-defined when the collection is empty (Hibernate logs a warning
        // and Postgres rejects an empty IN list). The early return preserves
        // the previous behaviour and protects the bulk query.
        if (refreshedSchemeCodes.isEmpty()) {
            return;
        }

        // B-69: single DB-side UPDATE replaces the previous
        // findAll().stream().filter(...).saveAll() pipeline, which pulled
        // every ProductScheme row (including the metadataJson blob — often
        // several KB each) into the JVM just to compute set difference.
        int deactivated = productSchemeRepository.deactivateActiveSchemesNotIn(refreshedSchemeCodes);
        if (deactivated > 0) {
            logger.info("product_scheme_refresh status='stale_schemes_deactivated' count='{}'", deactivated);
        }
    }
}
