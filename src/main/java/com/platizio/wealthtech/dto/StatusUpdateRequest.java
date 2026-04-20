package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

public record StatusUpdateRequest(@NotBlank String status, String reason) {}