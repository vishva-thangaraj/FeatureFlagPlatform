package com.feature_flag.platform.invitation.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.invitation.dto.InvitationResponse;
import com.feature_flag.platform.invitation.dto.UserInviteRequest;
import com.feature_flag.platform.invitation.service.InvitationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping
    public ResponseEntity<Void> inviteUserToTenant(@Valid @RequestBody UserInviteRequest request,
                                                    @AuthenticationPrincipal TenantUserDetails userDetails) {
        invitationService.inviteUserToTenant(userDetails, request.email(), request.role());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/accept")
    public ResponseEntity<InvitationResponse> acceptInvitation(@RequestParam String token) {
        InvitationResponse result = invitationService.acceptInvitation(token);
        return ResponseEntity.ok(result);
    }
}
