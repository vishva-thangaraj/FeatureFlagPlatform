package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateFeatureRequest(
        /*
         * The key is immutable once created and doubles as a URL path segment on the SDK-facing
         * endpoint (GET /api/v1/config/{featureKey}). A key containing '/' — "checkout/v2" — was
         * accepted here and then never matched @GetMapping("/{featureKey}"), leaving that flag's
         * config permanently unreachable with no way to rename it. Restrict it to what a path
         * segment can carry, the same way RuleRequest.attribute already does.
         */
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_.-]{0,49}$",
                message = "must start with a letter and contain only letters, digits, '_', '-' or '.'")
        String key,

        @Size(max = 255) String description
) {
}
