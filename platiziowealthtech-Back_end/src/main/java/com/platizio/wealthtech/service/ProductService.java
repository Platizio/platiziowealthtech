package com.platizio.wealthtech.service;

import com.platizio.wealthtech.common.DuplicateResourceException;
import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.dto.ProductSchemeRequest;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.data.domain.PageImpl;
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
    private final String catalogueSource;
    private final String catalogueEndpoint;

    private volatile Instant lastSuccessfulCatalogueSyncAt;

    public ProductService(
            ProductSchemeRepository productSchemeRepository,
            CybrillaClient cybrillaClient,
            @Value("${cybrilla.integration.catalogue-refresh-min-interval-minutes:25}") int catalogueRefreshMinIntervalMinutes,
            @Value("${cybrilla.integration.product-catalogue-source:cybrilla}") String catalogueSource,
            @Value("${cybrilla.integration.product-catalogue-endpoint:poa-mf}") String catalogueEndpoint
    ) {
        this.productSchemeRepository = productSchemeRepository;
        this.cybrillaClient = cybrillaClient;
        this.catalogueRefreshMinInterval = Duration.ofMinutes(Math.max(0, catalogueRefreshMinIntervalMinutes));
        this.catalogueSource = catalogueSource == null ? "cybrilla" : catalogueSource.trim().toLowerCase();
        this.catalogueEndpoint = catalogueEndpoint == null ? "poa-mf" : catalogueEndpoint.trim().toLowerCase();
    }

    public List<ProductScheme> listSchemes() {
        return productSchemeRepository.findAll();
    }

    public boolean usesCybrillaCatalogueByDefault() {
        return !"local".equals(catalogueSource);
    }

    public String configuredCatalogueEndpoint() {
        return catalogueEndpoint;
    }

    /** Raw Finprim catalogue page for the configured endpoint (default POA MF plans). */
    public JsonNode fetchLiveCataloguePage(int page, int size) {
        return fetchLiveCataloguePage(catalogueEndpoint, page, size);
    }

    public JsonNode fetchLiveCataloguePage(String endpoint, int page, int size) {
        return cybrillaClient.fetchLiveCataloguePage(endpoint, page, size).rawResponse();
    }

    public Page<ProductScheme> listSchemesPageFromCybrilla(
            String query,
            Boolean active,
            String assetClass,
            String category,
            String productType,
            int page,
            int size
    ) {
        return listSchemesPageFromCybrilla(catalogueEndpoint, query, active, assetClass, category, productType, page, size);
    }

    public Page<ProductScheme> listSchemesPageFromCybrilla(
            String endpoint,
            String query,
            Boolean active,
            String assetClass,
            String category,
            String productType,
            int page,
            int size
    ) {
        CybrillaClient.LiveCataloguePage live = cybrillaClient.fetchLiveCataloguePage(endpoint, page, size);
        List<ProductScheme> filtered = filterLiveSchemes(live.schemes(), query, active, assetClass, category, productType);
        List<ProductScheme> persisted = upsertLiveSchemes(filtered);
        return new PageImpl<>(persisted, schemePageRequest(page, size), live.totalElements());
    }

    public Page<ProductScheme> resolveSchemesPage(
            boolean local,
            String query,
            Boolean active,
            String assetClass,
            String category,
            String productType,
            int page,
            int size
    ) {
        if (local || "local".equals(catalogueSource)) {
            return listSchemesPage(query, active, assetClass, category, productType, page, size);
        }
        return listSchemesPageFromCybrilla(query, active, assetClass, category, productType, page, size);
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

        List<ProductScheme> saved = upsertLiveSchemes(latest);
        List<String> activeCodes = saved.stream()
                .map(ProductScheme::getExternalSchemeCode)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        int deactivated = productSchemeRepository.deactivateActiveSchemesNotIn(activeCodes, EXTERNAL_FUND_CATEGORIES);
        int removedUnsynced = productSchemeRepository.deleteByExternalFetchRequestJsonIsNull();
        logger.info(
                "product_scheme_refresh status='catalogue_upserted' upserted_count='{}' deactivated_count='{}' removed_unsynced='{}'",
                saved.size(),
                deactivated,
                removedUnsynced
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
        return syncAvailableFundsFromCybrilla(
                false,
                forceCatalogueRefresh,
                query,
                active,
                assetClass,
                category,
                productType,
                page,
                size
        );
    }

    @Transactional
    public Page<ProductScheme> syncAvailableFundsFromCybrilla(
            boolean explicitSyncRequest,
            boolean forceCatalogueRefresh,
            String query,
            Boolean active,
            String assetClass,
            String category,
            String productType,
            int page,
            int size
    ) {
        if (usesCybrillaCatalogueByDefault() && !explicitSyncRequest) {
            return listSchemesPageFromCybrilla(query, active, assetClass, category, productType, page, size);
        }
        return refreshThenListOrCachedFallback(
                forceCatalogueRefresh || explicitSyncRequest,
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

    /**
     * Upserts a live Cybrilla catalogue page so UI rows and POST /orders receive
     * stable {@code product_schemes.id} values instead of transient in-memory rows.
     */
    private List<ProductScheme> upsertLiveSchemes(List<ProductScheme> liveSchemes) {
        if (liveSchemes == null || liveSchemes.isEmpty()) {
            return List.of();
        }
        List<ProductScheme> persisted = new ArrayList<>(liveSchemes.size());
        for (ProductScheme live : liveSchemes) {
            String externalCode = trimToNull(live.getExternalSchemeCode());
            if (!StringUtils.hasText(externalCode)) {
                logger.warn(
                        "product_scheme_upsert status='skipped' reason='missing_external_scheme_code' scheme_name='{}'",
                        live.getSchemeName()
                );
                continue;
            }
            ProductScheme target = productSchemeRepository.findFirstByExternalSchemeCodeIgnoreCase(externalCode)
                    .orElseGet(ProductScheme::new);
            mergeLiveSchemeInto(target, live);
            persisted.add(productSchemeRepository.save(target));
        }
        return persisted;
    }

    private void mergeLiveSchemeInto(ProductScheme target, ProductScheme live) {
        target.setSchemeName(live.getSchemeName());
        target.setAmcName(live.getAmcName());
        target.setCategory(live.getCategory());
        target.setExternalSchemeCode(live.getExternalSchemeCode());
        target.setExternalIsin(live.getExternalIsin());
        target.setProductType(live.getProductType());
        target.setActive(live.getActive());
        target.setMetadataJson(live.getMetadataJson());
        target.setExternalFetchRequestJson(live.getExternalFetchRequestJson());
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

    private List<ProductScheme> filterLiveSchemes(
            List<ProductScheme> schemes,
            String query,
            Boolean active,
            String assetClass,
            String category,
            String productType
    ) {
        String queryPattern = likePattern(query);
        ProductCategory categoryFilter = parseCategory(category);
        String assetClassFilter = normalizeAssetClass(assetClass);
        String productTypeFilter = normalizeLower(productType);
        return schemes.stream()
                .filter(scheme -> active == null || active.equals(scheme.getActive()))
                .filter(scheme -> categoryFilter == null || scheme.getCategory() == categoryFilter)
                .filter(scheme -> assetClassFilter == null || matchesAssetClass(scheme, assetClassFilter))
                .filter(scheme -> productTypeFilter == null || productTypeFilter.equals(normalizeLower(scheme.getProductType())))
                .filter(scheme -> queryPattern == null || matchesQuery(scheme, queryPattern))
                .toList();
    }

    private boolean matchesAssetClass(ProductScheme scheme, String assetClassFilter) {
        if ("MF".equals(assetClassFilter)) {
            return scheme.getCategory() != ProductCategory.SIF;
        }
        if ("SIF".equals(assetClassFilter)) {
            return scheme.getCategory() == ProductCategory.SIF;
        }
        return true;
    }

    private boolean matchesQuery(ProductScheme scheme, String queryPattern) {
        String normalizedPattern = queryPattern.toLowerCase(java.util.Locale.ROOT);
        String haystack = String.join(
                " ",
                normalizeLower(scheme.getSchemeName()),
                normalizeLower(scheme.getAmcName()),
                normalizeLower(scheme.getExternalSchemeCode()),
                normalizeLower(scheme.getExternalIsin())
        );
        return haystack != null && haystack.contains(normalizedPattern.replace("%", ""));
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
