package com.feature_flag.platform.tenant.mapper;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.tenant.dto.MemberResponse;
import com.feature_flag.platform.tenant.dto.RoleResponse;
import com.feature_flag.platform.tenant.entity.Membership;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
public class MembershipListResponseMapper {

    public List<MemberResponse> toMemberResponses(List<Membership> memberships) {
        if (memberships == null || memberships.isEmpty()) {
            return List.of();
        }

        return memberships.stream()
                .map(this::toMemberResponse)
                .toList();
    }

    private MemberResponse toMemberResponse(Membership membership) {
        User user = membership.getUser();
        Set<Role> roles = membership.getRoles() == null ? Collections.emptySet() : membership.getRoles();

        List<RoleResponse> roleResponses = roles.stream()
                .map(role -> new RoleResponse(role.getId(), role.getName()))
                .toList();

        return new MemberResponse(
                membership.getId(),
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                roleResponses,
                membership.getStatus()
        );
    }
}
