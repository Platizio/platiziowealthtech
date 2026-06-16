package com.platizio.wealthtech.init;

import com.platizio.wealthtech.service.ProductService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "cybrilla.integration",
        name = "bootstrap-catalogue-on-startup",
        havingValue = "true"
)
public class DataInitializer implements CommandLineRunner {

    private final ProductService productService;

    public DataInitializer(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public void run(String... args) throws Exception {
        // Only bootstrap an empty catalogue; never pull thousands of schemes (and Finprim tokens) on every restart.
        if (productService.listSchemes().size() < 3) {
            productService.refreshFromCybrilla(true);
        }
    }
}