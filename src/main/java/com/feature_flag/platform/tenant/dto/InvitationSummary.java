package com.feature_flag.platform.tenant.dto;

import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.tenant.enums.InvitationStatus;

import java.time.Instant;

/**
 * An outstanding invitation, as an admin sees it on the team screen.
 *
 * <p>Deliberately carries no token: the token is the credential in the invitee's email, and an admin
 * listing does not need it. That is also why resending mints a new one — only the hash is stored, so the
 * original link is unrecoverable by design.
 *
 * <p>{@code status} is computed for display, not read straight off the row. An invitation whose
 * {@code expiresAt} has passed is still stored as {@code PENDING} until something touches it, so a listing
 * that reported the stored value would show an admin a live invitation that no longer works.
 */
public record InvitationSummary(
        String id,
        String email,
        RoleName role,
        InvitationStatus status,
        String invitedByEmail,
        Instant createdAt,
        Instant expiresAt
) {
}
