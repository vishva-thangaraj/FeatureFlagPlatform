package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.tenant.dto.MemberResponse;
import com.feature_flag.platform.tenant.dto.MembersListResponse;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.mapper.MembershipListResponseMapper;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MembershipService {
    private final MembershipRepository membershipRepository;
    private final MembershipListResponseMapper membershipListResponseMapper;

    public MembershipService(MembershipRepository membershipRepository,
                             MembershipListResponseMapper membershipListResponseMapper) {
        this.membershipRepository = membershipRepository;
        this.membershipListResponseMapper = membershipListResponseMapper;
    }

    public MembersListResponse getMembersList(Long tenantID) {
        List<Membership> memberships = membershipRepository.findByTenantId(tenantID);
        List<MemberResponse> members = membershipListResponseMapper.toMemberResponses(memberships);

        return new MembersListResponse(members);
    }
}
