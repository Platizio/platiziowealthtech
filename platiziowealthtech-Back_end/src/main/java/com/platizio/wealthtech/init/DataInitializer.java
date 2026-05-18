package com.platizio.wealthtech.init;

import com.platizio.wealthtech.service.ProductService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private final ProductService productService;

    public DataInitializer(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (productService.listSchemes().size() < 3) {
            productService.refreshFromCybrilla();
        }
    }
}
