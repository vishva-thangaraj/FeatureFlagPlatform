package com.feature_flag.platform.auth.dto;

import java.util.List;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        Long userId,
        String email,
        Long tenantId,
        List<String> roles,
        long accessTokenExpiresIn
) {
}