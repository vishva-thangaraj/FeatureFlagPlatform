package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.tenant.dto.MembersListResponse;
import com.feature_flag.platform.tenant.service.MembershipService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
public class MembersController {
    private final MembershipService membershipService;

    public MembersController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping
    public ResponseEntity<MembersListResponse> getMembers(@RequestParam Long tenantID) {
        MembersListResponse response = membershipService.getMembersList(tenantID);
        return ResponseEntity.ok(response);
    }
}
