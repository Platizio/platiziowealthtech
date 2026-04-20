package com.platizio.wealthtech.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "🚀 Wealthtech Backend is Running Successfully! Use Postman to test /api/v1/... routes.";
    }
}
