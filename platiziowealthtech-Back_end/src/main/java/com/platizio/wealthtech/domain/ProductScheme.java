package com.platizio.wealthtech.domain;

import com.platizio.wealthtech.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_schemes")
public class ProductScheme extends BaseEntity {

    @Column(nullable = false)
    private String schemeName;

    @Column(nullable = false)
    private String amcName;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ProductCategory category;

    @Column(nullable = false, unique = true)
    private String externalSchemeCode;

    private String externalIsin;
    private String productType;
    private Boolean active = Boolean.TRUE;
    private String metadataJson;
    @Column(columnDefinition = "TEXT")
    private String externalFetchRequestJson;

    public String getSchemeName() { return schemeName; }
    public void setSchemeName(String schemeName) { this.schemeName = schemeName; }
    public String getAmcName() { return amcName; }
    public void setAmcName(String amcName) { this.amcName = amcName; }
    public ProductCategory getCategory() { return category; }
    public void setCategory(ProductCategory category) { this.category = category; }
    public String getExternalSchemeCode() { return externalSchemeCode; }
    public void setExternalSchemeCode(String externalSchemeCode) { this.externalSchemeCode = externalSchemeCode; }
    public String getExternalIsin() { return externalIsin; }
    public void setExternalIsin(String externalIsin) { this.externalIsin = externalIsin; }
    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getExternalFetchRequestJson() { return externalFetchRequestJson; }
    public void setExternalFetchRequestJson(String externalFetchRequestJson) { this.externalFetchRequestJson = externalFetchRequestJson; }
}
