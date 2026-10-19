package com.feature_flag.platform.tenant.dto;

/**
 * A role as the dashboard sees it. {@code id} is a string for the reason given on
 * {@link com.feature_flag.platform.auth.dto.AuthResponse}: every entity here draws its id from
 * {@code BaseEntity}'s {@code @Tsid} generator, roles included, so these are 64-bit values a
 * JavaScript {@code Number} cannot hold exactly.
 */
public record RoleResponse(
        String id,
        String name
) {
}
