package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.service.ProductService;
import java.util.List;
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
    public ProductScheme getScheme(@PathVariable java.util.UUID schemeId) {
        return productService.getScheme(schemeId);
    }

    @PostMapping("/schemes/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ProductScheme> refreshSchemes() {
        return productService.refreshFromCybrilla();
    }

    @DeleteMapping("/schemes/{schemeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteScheme(@PathVariable java.util.UUID schemeId) {
        productService.deleteScheme(schemeId);
    }
}
