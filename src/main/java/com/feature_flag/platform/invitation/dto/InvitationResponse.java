package com.feature_flag.platform.invitation.dto;

import com.feature_flag.platform.invitation.enums.InvitationStatus;

public record InvitationResponse(
        String message,
        InvitationStatus status
) {
}