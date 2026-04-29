package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.integration.CybrillaClient;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

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
        List<ProductScheme> latest = cybrillaClient.fetchProductSchemes();
        return productSchemeRepository.saveAll(latest);
    }

    @Transactional
    public void deleteScheme(java.util.UUID schemeId) {
        productSchemeRepository.deleteById(schemeId);
    }
}