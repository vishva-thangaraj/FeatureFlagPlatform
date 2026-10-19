package com.feature_flag.platform.tenant.dto;

import com.feature_flag.platform.tenant.enums.MembershipStatus;

import java.util.List;

/**
 * A tenant member as the dashboard sees it. {@code membershipId} and {@code userID} are strings for the
 * reason given on {@link com.feature_flag.platform.auth.dto.AuthResponse} — they are TSIDs, and a
 * membership id that has been rounded by a JavaScript {@code Number} addresses no membership, so the
 * role change or removal built from it would act on nothing.
 */
public record MemberResponse(
       String membershipId,
       String userID,
       String firstName,
       String lastName,
       List<RoleResponse> roles,
       MembershipStatus status
) {
}
