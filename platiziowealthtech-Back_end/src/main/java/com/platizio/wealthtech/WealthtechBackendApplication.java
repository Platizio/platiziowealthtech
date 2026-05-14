package com.platizio.wealthtech;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WealthtechBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(WealthtechBackendApplication.class, args);
    }
}
