package com.feature_flag.platform.feature.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial update, matching {@link UpdateEnvRequest}: a {@code null} field means "leave this one
 * alone", and an empty string clears it. The two endpoints used to disagree — {@code PUT} on a
 * feature with {@code {}} wiped the description, while the same call on an environment left it
 * untouched.
 */
public record UpdateFeatureRequest(
        @Size(max = 255) String description
) {
}
