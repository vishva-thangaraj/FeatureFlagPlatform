package com.feature_flag.platform.tenant.dto;

import com.feature_flag.platform.tenant.enums.MembershipStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A tenant member as the dashboard sees it. {@code membershipId} and {@code userID} are strings for the
 * reason given on {@link com.feature_flag.platform.auth.dto.AuthResponse} — they are TSIDs, and a
 * membership id that has been rounded by a JavaScript {@code Number} addresses no membership, so the
 * role change or removal built from it would act on nothing.
 *
 * <p>{@code email} is here because a members table cannot otherwise say who someone is: first and last
 * name are not unique, and {@code lastName} is optional, so two "Jane" rows were indistinguishable. It is
 * already visible to anyone who can read this list -- they invited them by that address.
 */
public record MemberResponse(
       String membershipId,
       String userID,
       String email,
       String firstName,
       @Schema(nullable = true) String lastName,
       List<RoleResponse> roles,
       MembershipStatus status
) {
}
