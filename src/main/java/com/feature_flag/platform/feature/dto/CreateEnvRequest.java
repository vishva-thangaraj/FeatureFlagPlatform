package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateEnvRequest(
        /*
         * The name is the {@code envName} every SDK sends on /api/v1/config, so it has to survive a
         * round trip through a query string. Unconstrained, {"name":"   "} stored a blank name that
         * no client could ever ask for, and a 300-character name blew past the varchar(255) column
         * as a database constraint violation rather than a 400.
         */
        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_.-]{0,49}$",
                message = "must start with a letter and contain only letters, digits, '_', '-' or '.'")
        String name,

        @Size(max = 255) String description
) {
}
