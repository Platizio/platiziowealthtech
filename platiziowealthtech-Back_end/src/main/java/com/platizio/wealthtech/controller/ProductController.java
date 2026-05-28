package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.dto.ProductSchemeRequest;
import com.platizio.wealthtech.dto.ProductSchemeStatusRequest;
import com.platizio.wealthtech.service.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
    public List<ProductScheme> listSchemes() {
        return productService.listSchemes();
    }

    @GetMapping("/schemes/{schemeId}")
    public ProductScheme getScheme(@PathVariable UUID schemeId) {
        return productService.getScheme(schemeId);
    }

    @PostMapping("/schemes")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductScheme createScheme(@Valid @RequestBody ProductSchemeRequest request) {
        return productService.createScheme(request);
    }

    @PutMapping("/schemes/{schemeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductScheme updateScheme(@PathVariable UUID schemeId, @Valid @RequestBody ProductSchemeRequest request) {
        return productService.updateScheme(schemeId, request);
    }

    @PatchMapping("/schemes/{schemeId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductScheme updateSchemeStatus(@PathVariable UUID schemeId, @Valid @RequestBody ProductSchemeStatusRequest request) {
        return productService.updateSchemeStatus(schemeId, request.active());
    }

    @PostMapping("/schemes/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ProductScheme> refreshSchemes() {
        return productService.refreshFromCybrilla();
    }

    @DeleteMapping("/schemes/{schemeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteScheme(@PathVariable UUID schemeId) {
        productService.deleteScheme(schemeId);
    }
}
