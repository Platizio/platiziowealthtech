package com.platizio.wealthtech.dto;

import jakarta.validation.constraints.NotNull;

public record ProductSchemeStatusRequest(
        @NotNull Boolean active
) {
}
