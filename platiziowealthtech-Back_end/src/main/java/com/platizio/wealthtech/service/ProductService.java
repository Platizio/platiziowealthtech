package com.platizio.wealthtech.service;

import com.platizio.wealthtech.common.DuplicateResourceException;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.dto.ProductSchemeRequest;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    public ProductScheme getScheme(UUID schemeId) {
        return productSchemeRepository.findById(schemeId)
                .orElseThrow(() -> new EntityNotFoundException("Scheme not found"));
    }

    @Transactional
    public ProductScheme createScheme(ProductSchemeRequest request) {
        ProductScheme scheme = new ProductScheme();
        applyRequest(scheme, request, true);
        return productSchemeRepository.save(scheme);
    }

    @Transactional
    public ProductScheme updateScheme(UUID schemeId, ProductSchemeRequest request) {
        ProductScheme scheme = getScheme(schemeId);
        applyRequest(scheme, request, false);
        return productSchemeRepository.save(scheme);
    }

    @Transactional
    public ProductScheme updateSchemeStatus(UUID schemeId, boolean active) {
        ProductScheme scheme = getScheme(schemeId);
        scheme.setActive(active);
        return productSchemeRepository.save(scheme);
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
    public void deleteScheme(UUID schemeId) {
        if (!productSchemeRepository.existsById(schemeId)) {
            throw new EntityNotFoundException("Scheme not found");
        }
        productSchemeRepository.deleteById(schemeId);
    }

    private void applyRequest(ProductScheme scheme, ProductSchemeRequest request, boolean creating) {
        String schemeName = requireText(request.schemeName(), "Scheme name is required");
        String amcName = requireText(request.amcName(), "AMC name is required");
        String externalSchemeCode = requireText(request.externalSchemeCode(), "External scheme code is required");

        productSchemeRepository.findFirstByExternalSchemeCodeIgnoreCase(externalSchemeCode)
                .filter(existing -> !existing.getId().equals(scheme.getId()))
                .ifPresent(existing -> {
                    throw new DuplicateResourceException("A product scheme with this scheme code already exists");
                });

        ProductCategory category = request.category() == null ? ProductCategory.MF : request.category();
        scheme.setSchemeName(schemeName);
        scheme.setAmcName(amcName);
        scheme.setCategory(category);
        scheme.setExternalSchemeCode(externalSchemeCode);
        scheme.setExternalIsin(trimToNull(request.externalIsin()));
        scheme.setProductType(defaultProductType(category, request.productType()));
        if (creating || request.active() != null) {
            scheme.setActive(request.active() == null || request.active());
        }
        scheme.setMetadataJson(trimToNull(request.metadataJson()));
    }

    private String requireText(String value, String message) {
        String trimmed = trimToNull(value);
        if (!StringUtils.hasText(trimmed)) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String defaultProductType(ProductCategory category, String productType) {
        String trimmed = trimToNull(productType);
        if (trimmed != null) {
            return trimmed;
        }
        return category == ProductCategory.SIF ? "SIF" : "MUTUAL_FUND";
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
