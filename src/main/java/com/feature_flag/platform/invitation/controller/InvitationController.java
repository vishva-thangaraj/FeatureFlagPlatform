package com.feature_flag.platform.invitation.controller;

import com.feature_flag.platform.auth.dto.RegisterRequest;
import com.feature_flag.platform.auth.service.AuthService;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.invitation.dto.InvitationResponse;
import com.feature_flag.platform.invitation.dto.UserInviteRequest;
import com.feature_flag.platform.invitation.service.InvitationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final InvitationService invitationService;
    private final AuthService authService;

    public InvitationController(InvitationService invitationService, AuthService authService) {
        this.invitationService = invitationService;
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<Void> inviteUserToTenant(@Valid @RequestBody UserInviteRequest request,
                                                    @AuthenticationPrincipal TenantUserDetails userDetails) {
        invitationService.inviteUserToTenant(userDetails, request.email(), request.role());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{inviteToken}/accept")
    public ResponseEntity<InvitationResponse> acceptInvitation(@PathVariable("inviteToken") String token,
                                                                @RequestBody(required = false) @Valid RegisterRequest request) {
        if (request != null) {
            authService.register(request);
        }
        InvitationResponse result = invitationService.acceptInvitation(token);
        return ResponseEntity.ok(result);
    }
    @PostMapping("/{inviteToken}/decline")
    public ResponseEntity<Void> declineInvitation(@PathVariable("inviteToken") String token) {
        invitationService.declineInvitation(token);
        ResponseEntity.status(204).build();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{inviteToken}")
    public ResponseEntity<InvitationResponse> getInvitationStatus(@PathVariable String token) {
        InvitationResponse response = invitationService.getStatus(token);
        return ResponseEntity.ok(response);
    }

}
