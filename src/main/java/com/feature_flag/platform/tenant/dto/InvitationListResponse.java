package com.feature_flag.platform.tenant.dto;

import java.util.List;

public record InvitationListResponse(
        List<InvitationSummary> invitations
) {
}
