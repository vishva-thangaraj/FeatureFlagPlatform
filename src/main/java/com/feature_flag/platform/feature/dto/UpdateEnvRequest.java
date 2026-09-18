package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update: a {@code null} field means "leave this one alone". A supplied name still has to be
 * a usable {@code envName} (see {@link CreateEnvRequest}) — {@code @Pattern} and {@code @Size} both
 * pass on {@code null}, so they constrain only the fields actually sent.
 */
public record UpdateEnvRequest(
        @Size(max = 50)
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_.-]{0,49}$",
                message = "must start with a letter and contain only letters, digits, '_', '-' or '.'")
        String name,

        @Size(max = 255) String description
) {
}
