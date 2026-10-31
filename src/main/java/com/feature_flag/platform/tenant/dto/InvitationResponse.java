package com.feature_flag.platform.tenant.dto;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.tenant.enums.InvitationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @param status what the invitation means to <em>this caller</em>, which is not always the row's stored
 *               status. {@code PENDING_UNREGISTERED} in particular is a response-only value: it says "this
 *               invitation is still PENDING and the invited address has no account yet, so send a
 *               registration", and the stored row stays {@code PENDING} throughout. Nothing persists it,
 *               deliberately — an invitation waiting on a registration is not in a different state from one
 *               waiting on a sign-in, and giving it its own stored status would mean two ways to spell
 *               "pending" for {@code requirePendingInvitation} to keep in step.
 *               <p>{@code VALIDATED} and {@code CANCELLED} are likewise never assigned anywhere; see
 *               {@code AuthServiceImpl.register} for why the auto-link that was to have set
 *               {@code VALIDATED} was removed rather than made real.
 */
public record InvitationResponse(
        String message,
        InvitationStatus status,
        @Schema(nullable = true) AuthResponse authResponse
) {
}