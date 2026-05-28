package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.ProductCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductSchemeRequest(
        @NotBlank @Size(max = 255) String schemeName,
        @NotBlank @Size(max = 255) String amcName,
        @NotNull ProductCategory category,
        @NotBlank @Size(max = 255) String externalSchemeCode,
        @Size(max = 100) String externalIsin,
        @Size(max = 100) String productType,
        Boolean active,
        String metadataJson
) {
}
