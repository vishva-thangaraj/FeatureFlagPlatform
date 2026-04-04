package com.feature_flag.platform.tenant.dto;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.tenant.enums.InvitationStatus;

public record InvitationResponse(
        String message,
        InvitationStatus status,
        AuthResponse authResponse
) {
}