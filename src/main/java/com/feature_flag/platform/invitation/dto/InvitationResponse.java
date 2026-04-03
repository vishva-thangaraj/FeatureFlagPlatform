package com.feature_flag.platform.invitation.dto;

public record InvitationResponse(
        String message,
        boolean registered
) {
}