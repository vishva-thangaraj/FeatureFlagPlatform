package com.feature_flag.platform.dto.tenant;

public record UserInviteRequest(
        String email,
        String role
) {
}
