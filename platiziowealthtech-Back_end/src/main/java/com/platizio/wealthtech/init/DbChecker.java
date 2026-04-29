package com.platizio.wealthtech.init;

import com.platizio.wealthtech.domain.ProductCategory;
import com.platizio.wealthtech.repository.ProductSchemeRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class DbChecker implements CommandLineRunner {

    private final ProductSchemeRepository repository;

    public DbChecker(ProductSchemeRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("--- Product Scheme Database Check ---");
        repository.findAll().forEach(s -> {
            System.out.println("Scheme: " + s.getSchemeName() + " | Category: " + s.getCategory());
        });
        System.out.println("Total schemes: " + repository.count());
        System.out.println("-------------------------------------");
    }
}
