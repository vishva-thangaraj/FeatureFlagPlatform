package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.dto.RegisterRequest;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.InvitationListResponse;
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

    /** The tenant's outstanding invitations, for the team screen. */
    InvitationListResponse listInvitations(Long tenantId);

    /**
     * Withdraws an invitation that has not been acted on, finally assigning {@code CANCELLED}. Until this
     * existed an admin who invited the wrong address had no way to take it back short of waiting out the
     * seven-day expiry, and could not re-invite the right one either, because a live PENDING invitation
     * for that address is a 409.
     */
    void cancelInvitation(Long invitationId, TenantUserDetails caller);

    /**
     * Sends the invitation again, with a fresh token and a fresh expiry.
     *
     * <p>The token necessarily changes: only its hash is stored, so the original link cannot be
     * reproduced. The previous link therefore stops working, which is the right outcome anyway — a resend
     * is how an admin recovers from an invitation that leaked or expired.
     */
    void resendInvitation(Long invitationId, TenantUserDetails caller);
}
