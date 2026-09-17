package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.dto.RegisterRequest;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.InvitationResponse;

public interface InvitationService {

    /**
     * Accepts an invitation for the invited person only.
     *
     * @param rawToken     the invite token from the invitation mail — proof that the invitation exists,
     *                     never proof of who the caller is.
     * @param registration supplied only by an invitee who has no account yet; its email must match the
     *                     invited address. Null (and ignored) for an already-registered invitee.
     * @param caller       the authenticated principal, or null when the request carries no credentials —
     *                     an anonymous principal must be passed as null.
     */
    InvitationResponse acceptInvitation(String rawToken, RegisterRequest registration,
                                        TenantUserDetails caller);

    void inviteUserToTenant(TenantUserDetails userDetails, String email, String role);

    InvitationResponse getStatus(String token);

    void declineInvitation(String token);
}
