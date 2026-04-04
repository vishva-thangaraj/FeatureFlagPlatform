package com.feature_flag.platform.tenant.dto;


import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.enums.MembershipStatus;

import java.util.List;

public record MemberResponse(
       Long membershipId,
       Long userID,
       String firstName,
       String lastName,
       List<RoleResponse> roles,
       MembershipStatus status

) {
}
