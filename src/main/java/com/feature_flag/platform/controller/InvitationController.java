package com.feature_flag.platform.controller;

import com.feature_flag.platform.service.InvitationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/accept")
    public ResponseEntity<InvitationService.InvitationResult> acceptInvitation(@RequestParam String token) {
        InvitationService.InvitationResult result = invitationService.acceptInvitation(token);
        return ResponseEntity.ok(result);
    }
}
