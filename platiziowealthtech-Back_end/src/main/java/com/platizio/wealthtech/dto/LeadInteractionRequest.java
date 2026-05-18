package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotBlank;

public record LeadInteractionRequest(
        @NotBlank String commentText,
        String interactionType
) {}
