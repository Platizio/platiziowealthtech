package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.dto.ProductSchemeRequest;
import com.platizio.wealthtech.dto.ProductSchemeStatusRequest;
import com.platizio.wealthtech.service.ProductService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/schemes")
    public Page<ProductScheme> listSchemes(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String assetClass,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String productType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return productService.listSchemesPage(query, active, assetClass, category, productType, page, size);
    }

    @GetMapping("/schemes/page")
    public Page<ProductScheme> listAvailableSchemesPage(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String assetClass,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String productType,
            @RequestParam(defaultValue = "false") boolean syncFromCybrilla,
            @RequestParam(defaultValue = "false") boolean forceCatalogueRefresh,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Boolean effectiveActive = active == null ? Boolean.TRUE : active;
        if (syncFromCybrilla) {
            return productService.syncAvailableFundsFromCybrilla(
                    forceCatalogueRefresh,
                    query,
                    effectiveActive,
                    assetClass,
                    category,
                    productType,
                    page,
                    size
            );
        }
        return productService.listSchemesPage(
                query,
                effectiveActive,
                assetClass,
                category,
                productType,
                page,
                size
        );
    }

    @GetMapping("/schemes/{schemeId}")
    public ProductScheme getScheme(@PathVariable UUID schemeId) {
        return productService.getScheme(schemeId);
    }

    @PostMapping("/schemes")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')")
    public ProductScheme createScheme(@Valid @RequestBody ProductSchemeRequest request) {
        return productService.createScheme(request);
    }

    @PutMapping("/schemes/{schemeId}")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')")
    public ProductScheme updateScheme(@PathVariable UUID schemeId, @Valid @RequestBody ProductSchemeRequest request) {
        return productService.updateScheme(schemeId, request);
    }

    @PatchMapping("/schemes/{schemeId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')")
    public ProductScheme updateSchemeStatus(@PathVariable UUID schemeId, @Valid @RequestBody ProductSchemeStatusRequest request) {
        return productService.updateSchemeStatus(schemeId, request.active());
    }

    @PostMapping("/schemes/refresh")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')")
    public Page<ProductScheme> refreshSchemes(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String assetClass,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String productType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return productService.syncAvailableFundsFromCybrilla(true, query, active, assetClass, category, productType, page, size);
    }

    @PostMapping({"/funds/sync", "/schemes/sync"})
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')")
    public Page<ProductScheme> syncFundsFromCybrilla(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String assetClass,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String productType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return productService.syncAvailableFundsFromCybrilla(true, query, active, assetClass, category, productType, page, size);
    }

    @DeleteMapping("/schemes/{schemeId}")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DISTRIBUTOR')")
    public void deleteScheme(@PathVariable UUID schemeId) {
        productService.deleteScheme(schemeId);
    }
}
