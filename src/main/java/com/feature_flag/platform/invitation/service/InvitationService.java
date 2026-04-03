package com.feature_flag.platform.invitation.service;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.invitation.dto.InvitationResponse;

public interface InvitationService {

    InvitationResponse acceptInvitation(String rawToken);

    void inviteUserToTenant(TenantUserDetails userDetails, String email, String role);

    InvitationResponse getStatus(String token);

    void declineInvitation(String token);
}
