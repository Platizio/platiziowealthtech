package com.platizio.wealthtech.dto;

import com.platizio.wealthtech.domain.ContactChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body for self-declaring that an email / mobile belongs to the investor.
 * {@code belongsTo} is the FP relationship enum: self | spouse | dependent_child
 * | dependent_parent | guardian.
 */
public record ContactDeclarationRequest(
        @NotNull ContactChannel channel,
        @NotBlank String belongsTo
) {}
