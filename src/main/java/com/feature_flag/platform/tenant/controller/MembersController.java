package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.MemberResponse;
import com.feature_flag.platform.tenant.dto.MembersListResponse;
import com.feature_flag.platform.tenant.dto.UpdateMemberRoleRequest;
import com.feature_flag.platform.tenant.service.MembershipService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/members")
public class MembersController {
    private final MembershipService membershipService;

    public MembersController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping
    public ResponseEntity<MembersListResponse> getMembers(@AuthenticationPrincipal TenantUserDetails userDetails) {
        MembersListResponse response = membershipService.getMembersList(userDetails.getTenantId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{membershipId}")
    public ResponseEntity<MemberResponse> getMember(@PathVariable Long membershipId,
                                                    @AuthenticationPrincipal TenantUserDetails userDetails) {
        MemberResponse response = membershipService.getMember(membershipId, userDetails.getTenantId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{membershipId}/role")
    public ResponseEntity<MemberResponse> updateMemberRole(@PathVariable Long membershipId,
                                                           @Valid @RequestBody UpdateMemberRoleRequest request,
                                                           @AuthenticationPrincipal TenantUserDetails userDetails) {
        MemberResponse response = membershipService.updateMemberRole(membershipId, request, userDetails.getTenantId());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{membershipId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long membershipId,
                                             @AuthenticationPrincipal TenantUserDetails userDetails) {
        membershipService.removeMember(membershipId, userDetails.getTenantId());
        return ResponseEntity.noContent().build();
    }
}
