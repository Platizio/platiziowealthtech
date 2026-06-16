package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class ProductServiceTest {

    @Test
    void syncAvailableFundsFetchesCybrillaReplacesImportedSchemesThenReturnsPagedLocalResults() {
        ProductSchemeRepository repository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        ProductScheme fetched = scheme("INF001", "Alpha Fund Updated");
        ProductScheme listed = scheme("INF001", "Alpha Fund Updated");
        setId(listed, UUID.randomUUID());
        Page<ProductScheme> localPage = new PageImpl<>(List.of(listed));

        when(cybrillaClient.fetchProductSchemes())
                .thenReturn(CybrillaClient.SchemeFetchResult.complete(List.of(fetched)));
        when(repository.findFirstByExternalSchemeCodeIgnoreCase("INF001")).thenReturn(java.util.Optional.empty());
        when(repository.save(any(ProductScheme.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.deleteStalePurchaseSchemesNotIn(anyList(), anyList())).thenReturn(2);
        when(repository.searchSchemes(eq(null), eq(Boolean.TRUE), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(localPage);

        ProductService service = productService(repository, cybrillaClient);
        Page<ProductScheme> result = service.syncAvailableFundsFromCybrilla(0, 20);

        ArgumentCaptor<ProductScheme> savedScheme = ArgumentCaptor.forClass(ProductScheme.class);
        verify(repository).save(savedScheme.capture());

        assertThat(savedScheme.getValue()).satisfies(saved -> {
            assertThat(saved.getSchemeName()).isEqualTo("Alpha Fund Updated");
            assertThat(saved.getExternalSchemeCode()).isEqualTo("INF001");
        });
        assertThat(result.getContent()).containsExactly(listed);
        InOrder ordered = inOrder(cybrillaClient, repository);
        ordered.verify(cybrillaClient).fetchProductSchemes();
        ordered.verify(repository).findFirstByExternalSchemeCodeIgnoreCase("INF001");
        ordered.verify(repository).save(any(ProductScheme.class));
        ordered.verify(repository).deleteStalePurchaseSchemesNotIn(anyList(), anyList());
        ordered.verify(repository).searchSchemes(eq(null), eq(Boolean.TRUE), eq(null), eq(null), eq(null), any(Pageable.class));
        verify(repository, never()).deleteSchemesByCategoryIn(any());
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void syncAvailableFundsReturnsCachedResultsWhenCybrillaIsRateLimited() {
        ProductSchemeRepository repository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        ProductScheme cached = scheme("INF001", "Cached Alpha Fund");
        Page<ProductScheme> localPage = new PageImpl<>(List.of(cached));

        when(cybrillaClient.fetchProductSchemes())
                .thenThrow(new CybrillaApiException("Too Many Requests"));
        when(repository.searchSchemes(eq(null), eq(Boolean.TRUE), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(localPage);

        ProductService service = productService(repository, cybrillaClient);
        Page<ProductScheme> result = service.syncAvailableFundsFromCybrilla(0, 20);

        assertThat(result.getContent()).containsExactly(cached);
        verify(repository, never()).saveAll(anyList());
        verify(repository, never()).deleteSchemesByCategoryIn(any());
        verify(repository, never()).deactivateActiveSchemesNotIn(any(), any());
    }

    @Test
    void syncAvailableFundsReturnsEmptyFilteredCacheWhenLocalCatalogueExists() {
        ProductSchemeRepository repository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        Page<ProductScheme> emptyFilteredPage = new PageImpl<>(List.of());

        when(cybrillaClient.fetchProductSchemes())
                .thenThrow(new CybrillaApiException("Too Many Requests"));
        when(repository.searchSchemes(eq("%alpha%"), eq(Boolean.TRUE), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(emptyFilteredPage);
        when(repository.count()).thenReturn(1L);

        ProductService service = productService(repository, cybrillaClient);
        Page<ProductScheme> result = service.syncAvailableFundsFromCybrilla("alpha", Boolean.TRUE, 0, 20);

        assertThat(result.getContent()).isEmpty();
        verify(repository, never()).saveAll(anyList());
        verify(repository, never()).deleteSchemesByCategoryIn(any());
        verify(repository, never()).deactivateActiveSchemesNotIn(any(), any());
    }

    @Test
    void refreshFromCybrillaRefusesToReplaceLocalCopyWhenFetchIsPartial() {
        ProductSchemeRepository repository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        ProductScheme fetched = scheme("INF001", "Alpha Fund");

        when(cybrillaClient.fetchProductSchemes())
                .thenReturn(CybrillaClient.SchemeFetchResult.partial(List.of(fetched), "max_pages_reached"));

        ProductService service = productService(repository, cybrillaClient);

        assertThatThrownBy(service::refreshFromCybrilla)
                .isInstanceOf(CybrillaApiException.class)
                .hasMessageContaining("partial");
        verify(repository, never()).deleteSchemesByCategoryIn(any());
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void syncAvailableFundsSkipsCybrillaWhenCatalogueWasSyncedRecentlyUnlessForced() {
        ProductSchemeRepository repository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        ProductScheme cached = scheme("INF001", "Cached Alpha Fund");
        Page<ProductScheme> localPage = new PageImpl<>(List.of(cached));

        when(repository.countByExternalFetchRequestJsonIsNotNull()).thenReturn(100L);
        when(repository.searchSchemes(eq(null), eq(Boolean.TRUE), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(localPage);

        ProductService service = productService(repository, cybrillaClient);
        ReflectionTestUtils.setField(service, "lastSuccessfulCatalogueSyncAt", Instant.now());

        Page<ProductScheme> result = service.syncAvailableFundsFromCybrilla(0, 20);

        assertThat(result.getContent()).containsExactly(cached);
        verify(cybrillaClient, never()).fetchProductSchemes();

        when(cybrillaClient.fetchProductSchemes())
                .thenReturn(CybrillaClient.SchemeFetchResult.complete(List.of(cached)));
        when(repository.findFirstByExternalSchemeCodeIgnoreCase("INF001")).thenReturn(java.util.Optional.of(cached));
        when(repository.save(any(ProductScheme.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.deactivateActiveSchemesNotIn(anyList(), anyList())).thenReturn(0);
        when(repository.deleteByExternalFetchRequestJsonIsNull()).thenReturn(0);

        service.syncAvailableFundsFromCybrilla(true, true, null, Boolean.TRUE, null, null, null, 0, 20);
        verify(cybrillaClient).fetchProductSchemes();
    }

    @Test
    void syncAvailableFundsUsesLiveCatalogueForBrowseButPersistsWhenExplicitSyncRequested() {
        ProductSchemeRepository repository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        ProductScheme live = scheme("INF001", "Live Alpha Fund");
        UUID browsePersistedId = UUID.randomUUID();
        ProductScheme persisted = scheme("INF001", "Live Alpha Fund");
        setId(persisted, UUID.randomUUID());
        Page<ProductScheme> localPage = new PageImpl<>(List.of(persisted));

        when(cybrillaClient.fetchLiveCataloguePage("poa-mf", 0, 20))
                .thenReturn(new CybrillaClient.LiveCataloguePage(null, List.of(live), 1L, 0, 20, "/v2/mf_scheme_plans/cybrillapoa"));
        when(cybrillaClient.fetchProductSchemes())
                .thenReturn(CybrillaClient.SchemeFetchResult.complete(List.of(live)));
        when(repository.findFirstByExternalSchemeCodeIgnoreCase("INF001")).thenReturn(java.util.Optional.empty());
        when(repository.save(any(ProductScheme.class))).thenAnswer(invocation -> {
            ProductScheme saved = invocation.getArgument(0);
            setId(saved, browsePersistedId);
            return saved;
        });
        when(repository.deactivateActiveSchemesNotIn(anyList(), anyList())).thenReturn(0);
        when(repository.deleteByExternalFetchRequestJsonIsNull()).thenReturn(0);
        when(repository.searchSchemes(eq(null), eq(Boolean.TRUE), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(localPage);

        ProductService service = liveProductService(repository, cybrillaClient);

        Page<ProductScheme> browse = service.syncAvailableFundsFromCybrilla(false, false, null, Boolean.TRUE, null, null, null, 0, 20);
        assertThat(browse.getContent()).singleElement().satisfies(saved -> assertThat(saved.getId()).isEqualTo(browsePersistedId));
        verify(repository).save(any(ProductScheme.class));
        verify(cybrillaClient, never()).fetchProductSchemes();

        Page<ProductScheme> synced = service.syncAvailableFundsFromCybrilla(true, false, null, Boolean.TRUE, null, null, null, 0, 20);
        assertThat(synced.getContent()).containsExactly(persisted);
        verify(cybrillaClient).fetchProductSchemes();
    }

    @Test
    void resolveSchemesPageUsesLiveCybrillaCatalogueByDefault() {
        ProductSchemeRepository repository = mock(ProductSchemeRepository.class);
        CybrillaClient cybrillaClient = mock(CybrillaClient.class);
        ProductScheme live = scheme("INF001", "Live Alpha Fund");
        UUID persistedId = UUID.randomUUID();
        when(cybrillaClient.fetchLiveCataloguePage("poa-mf", 0, 20))
                .thenReturn(new CybrillaClient.LiveCataloguePage(null, List.of(live), 1L, 0, 20, "/v2/mf_scheme_plans/cybrillapoa"));
        when(repository.findFirstByExternalSchemeCodeIgnoreCase("INF001")).thenReturn(java.util.Optional.empty());
        when(repository.save(any(ProductScheme.class))).thenAnswer(invocation -> {
            ProductScheme saved = invocation.getArgument(0);
            setId(saved, persistedId);
            return saved;
        });

        ProductService service = liveProductService(repository, cybrillaClient);
        Page<ProductScheme> result = service.resolveSchemesPage(false, null, Boolean.TRUE, null, null, null, 0, 20);

        assertThat(result.getContent()).singleElement().satisfies(saved -> assertThat(saved.getId()).isEqualTo(persistedId));
        verify(repository).save(any(ProductScheme.class));
        verify(repository, never()).searchSchemes(any(), any(), any(), any(), any(), any(Pageable.class));
    }

    private ProductService productService(ProductSchemeRepository repository, CybrillaClient cybrillaClient) {
        return new ProductService(repository, cybrillaClient, 25, "local", "poa-mf");
    }

    private ProductService liveProductService(ProductSchemeRepository repository, CybrillaClient cybrillaClient) {
        return new ProductService(repository, cybrillaClient, 25, "cybrilla", "poa-mf");
    }

    private void setId(ProductScheme scheme, UUID id) {
        ReflectionTestUtils.setField(scheme, "id", id);
    }

    private ProductScheme scheme(String code, String name) {
        ProductScheme scheme = new ProductScheme();
        scheme.setSchemeName(name);
        scheme.setAmcName("Test AMC");
        scheme.setCategory(ProductCategory.MF);
        scheme.setExternalSchemeCode(code);
        scheme.setExternalIsin(code);
        scheme.setProductType("MF");
        scheme.setActive(true);
        scheme.setMetadataJson("{\"id\":\"" + code + "\"}");
        return scheme;
    }
}
