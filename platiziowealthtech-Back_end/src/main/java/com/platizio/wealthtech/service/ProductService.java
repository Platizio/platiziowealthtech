package com.platizio.wealthtech.service;

import com.platizio.wealthtech.common.DuplicateResourceException;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.dto.ProductSchemeRequest;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SCHEME_SAVE_BATCH_SIZE = 250;
    private static final int MAX_SCHEME_PAGE_SIZE = 100;
    /** Categories replaced when importing the Finprim fund catalogue (includes Flyway seed rows). */
    private static final List<ProductCategory> EXTERNAL_FUND_CATEGORIES = List.of(
            ProductCategory.MF,
            ProductCategory.MUTUAL_FUND,
            ProductCategory.SIF,
            ProductCategory.OTHER,
            ProductCategory.EQUITY
    );

    private final ProductSchemeRepository productSchemeRepository;
    private final CybrillaClient cybrillaClient;
    private final Duration catalogueRefreshMinInterval;

    private volatile Instant lastSuccessfulCatalogueSyncAt;

    public ProductService(
            ProductSchemeRepository productSchemeRepository,
            CybrillaClient cybrillaClient,
            @Value("${cybrilla.integration.catalogue-refresh-min-interval-minutes:25}") int catalogueRefreshMinIntervalMinutes
    ) {
        this.productSchemeRepository = productSchemeRepository;
        this.cybrillaClient = cybrillaClient;
        this.catalogueRefreshMinInterval = Duration.ofMinutes(Math.max(0, catalogueRefreshMinIntervalMinutes));
    }

    public List<ProductScheme> listSchemes() {
        return productSchemeRepository.findAll();
    }

    public Page<ProductScheme> listSchemesPage(String query, Boolean active, int page, int size) {
        return listSchemesPage(query, active, null, null, null, page, size);
    }

    public Page<ProductScheme> listSchemesPage(
            String query,
            Boolean active,
            String assetClass,
            String category,
            String productType,
            int page,
            int size
    ) {
        return productSchemeRepository.searchSchemes(
                likePattern(query),
                active,
                normalizeAssetClass(assetClass),
                parseCategory(category),
                normalizeLower(productType),
                schemePageRequest(page, size)
        );
    }

    public Page<ProductScheme> listAvailableSchemesPage(int page, int size) {
        return listSchemesPage(null, Boolean.TRUE, page, size);
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
        return refreshFromCybrilla(false);
    }

    @Transactional
    public List<ProductScheme> refreshFromCybrilla(boolean force) {
        if (shouldSkipCatalogueRefresh(force)) {
            logger.info(
                    "product_scheme_refresh status='skipped_recent_sync' min_interval_minutes='{}'",
                    catalogueRefreshMinInterval.toMinutes()
            );
            return productSchemeRepository.findAll();
        }
        CybrillaClient.SchemeFetchResult fetched = cybrillaClient.fetchProductSchemes();
        List<ProductScheme> latest = dedupeByExternalSchemeCode(fetched.schemes());
        if (!fetched.complete()) {
            throw new CybrillaApiException(
                    "Cybrilla fund catalogue fetch was partial (" + fetched.incompleteReason()
                    + "); refusing to replace the local fund copy with incomplete data"
            );
        }

        // Full replace of Cybrilla-sourced MF + SIF catalogue rows (removes seed/dummy schemes).
        if (latest.isEmpty()) {
            throw new CybrillaApiException(
                    "Cybrilla returned no fund schemes; refusing to wipe the local catalogue with an empty import"
            );
        }

        int removed = productSchemeRepository.deleteSchemesByCategoryIn(EXTERNAL_FUND_CATEGORIES);
        int removedUnsynced = productSchemeRepository.deleteByExternalFetchRequestJsonIsNull();
        List<ProductScheme> saved = saveSchemesInBatches(latest);
        logger.info(
                "product_scheme_refresh status='catalogue_replaced' removed_count='{}' removed_unsynced='{}' saved_count='{}'",
                removed,
                removedUnsynced,
                saved.size()
        );
        lastSuccessfulCatalogueSyncAt = Instant.now();
        return saved;
    }

    @Transactional
    public Page<ProductScheme> syncAvailableFundsFromCybrilla(int page, int size) {
        return syncAvailableFundsFromCybrilla(false, null, Boolean.TRUE, null, null, null, page, size);
    }

    @Transactional
    public Page<ProductScheme> syncAvailableFundsFromCybrilla(String query, Boolean active, int page, int size) {
        return syncAvailableFundsFromCybrilla(false, query, active, null, null, null, page, size);
    }

    @Transactional
    public Page<ProductScheme> syncAvailableFundsFromCybrilla(
            String query,
            Boolean active,
            String assetClass,
            String category,
            String productType,
            int page,
            int size
    ) {
        return syncAvailableFundsFromCybrilla(false, query, active, assetClass, category, productType, page, size);
    }

    @Transactional
    public Page<ProductScheme> syncAvailableFundsFromCybrilla(
            boolean forceCatalogueRefresh,
            String query,
            Boolean active,
            String assetClass,
            String category,
            String productType,
            int page,
            int size
    ) {
        return refreshThenListOrCachedFallback(
                forceCatalogueRefresh,
                () -> listSchemesPage(query, active, assetClass, category, productType, page, size)
        );
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

    private boolean shouldSkipCatalogueRefresh(boolean force) {
        if (force || catalogueRefreshMinInterval.isZero()) {
            return false;
        }
        if (lastSuccessfulCatalogueSyncAt == null) {
            return false;
        }
        if (productSchemeRepository.countByExternalFetchRequestJsonIsNotNull() == 0) {
            return false;
        }
        return Duration.between(lastSuccessfulCatalogueSyncAt, Instant.now()).compareTo(catalogueRefreshMinInterval) < 0;
    }

    private Page<ProductScheme> refreshThenListOrCachedFallback(
            boolean forceCatalogueRefresh,
            Supplier<Page<ProductScheme>> localPageSupplier
    ) {
        try {
            refreshFromCybrilla(forceCatalogueRefresh);
        } catch (CybrillaApiException ex) {
            Page<ProductScheme> cachedPage = localPageSupplier.get();
            if (cachedPage.hasContent() || productSchemeRepository.count() > 0) {
                logger.warn(
                        "product_scheme_refresh status='fallback_to_cache' reason='{}' cached_page_count='{}'",
                        ex.getMessage(),
                        cachedPage.getNumberOfElements()
                );
                return cachedPage;
            }
            throw ex;
        }
        return localPageSupplier.get();
    }

    private List<ProductScheme> saveSchemesInBatches(List<ProductScheme> schemes) {
        List<ProductScheme> saved = new ArrayList<>(schemes.size());
        for (int offset = 0; offset < schemes.size(); offset += SCHEME_SAVE_BATCH_SIZE) {
            int end = Math.min(offset + SCHEME_SAVE_BATCH_SIZE, schemes.size());
            saved.addAll(productSchemeRepository.saveAll(schemes.subList(offset, end)));
        }
        return saved;
    }

    private String normalizeLower(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private String likePattern(String value) {
        String normalized = normalizeLower(value);
        return normalized == null ? null : "%" + normalized + "%";
    }

    private String defaultProductType(ProductCategory category, String productType) {
        String trimmed = trimToNull(productType);
        if (trimmed != null) {
            return trimmed;
        }
        return category == ProductCategory.SIF ? "SIF" : "MUTUAL_FUND";
    }

    private ProductCategory parseCategory(String category) {
        String trimmed = trimToNull(category);
        if (trimmed == null) {
            return null;
        }
        try {
            return ProductCategory.valueOf(trimmed.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warn("product_scheme_search status='ignored_filter' reason='unknown_category' category='{}'", trimmed);
            return null;
        }
    }

    private String normalizeAssetClass(String assetClass) {
        String trimmed = trimToNull(assetClass);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.toUpperCase(java.util.Locale.ROOT);
        if ("MF".equals(normalized) || "SIF".equals(normalized)) {
            return normalized;
        }
        logger.warn("product_scheme_search status='ignored_filter' reason='unknown_asset_class' asset_class='{}'", trimmed);
        return null;
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

    private PageRequest schemePageRequest(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_SCHEME_PAGE_SIZE),
                Sort.by(Sort.Order.asc("schemeName"), Sort.Order.asc("amcName"))
        );
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            logger.warn("external_snapshot status='serialize_failed' reason='{}'", ex.getMessage());
            return null;
        }
    }
}
