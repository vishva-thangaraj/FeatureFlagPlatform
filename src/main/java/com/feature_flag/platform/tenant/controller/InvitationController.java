package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.auth.dto.RegisterRequest;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.InvitationListResponse;
import com.feature_flag.platform.tenant.dto.InvitationResponse;
import com.feature_flag.platform.tenant.dto.UserInviteRequest;
import com.feature_flag.platform.tenant.service.InvitationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    /**
     * Serves both halves of acceptance, and the service decides which applies from whether the invited
     * address already has an account:
     *
     * <ul>
     *   <li>an already-registered invitee sends no body and must be authenticated as themselves;</li>
     *   <li>an invitee with no account yet sends a {@link RegisterRequest} for the invited address and
     *       legitimately has no credentials.</li>
     * </ul>
     *
     * <p>Because the second case has no session, the path stays {@code permitAll} and authorization lives
     * in the service. {@code userDetails} is null for an anonymous request — {@code @AuthenticationPrincipal}
     * does not bind the {@code "anonymousUser"} String to {@link TenantUserDetails} — so an unauthenticated
     * caller reaches the service as "no caller" rather than as a non-null {@code Authentication}, which is
     * exactly what the old {@code getAuthentication() == null} guard got wrong.
     *
     * <p>Registration is delegated to the service so that creating the account, the membership and the
     * status change all happen in one transaction.
     */
    @PostMapping("/{inviteToken}/accept")
    public ResponseEntity<InvitationResponse> acceptInvitation(@PathVariable("inviteToken") String token,
                                                                @RequestBody(required = false) @Valid RegisterRequest request,
                                                                @AuthenticationPrincipal TenantUserDetails userDetails) {
        InvitationResponse result = invitationService.acceptInvitation(token, request, userDetails);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{inviteToken}/decline")
    public ResponseEntity<Void> declineInvitation(@PathVariable("inviteToken") String token) {
        invitationService.declineInvitation(token);
        return ResponseEntity.noContent().build();
    }

    /**
     * The tenant's outstanding invitations. Addressed by id below, never by token: an admin screen acts on
     * invitations it can see, and the token belongs to the invitee's inbox.
     */
    @GetMapping
    public ResponseEntity<InvitationListResponse> listInvitations(
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        return ResponseEntity.ok(invitationService.listInvitations(userDetails.getTenantId()));
    }

    @PostMapping("/{invitationId}/cancel")
    public ResponseEntity<Void> cancelInvitation(@PathVariable Long invitationId,
                                                 @AuthenticationPrincipal TenantUserDetails userDetails) {
        invitationService.cancelInvitation(invitationId, userDetails);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{invitationId}/resend")
    public ResponseEntity<Void> resendInvitation(@PathVariable Long invitationId,
                                                 @AuthenticationPrincipal TenantUserDetails userDetails) {
        invitationService.resendInvitation(invitationId, userDetails);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{inviteToken}")
    public ResponseEntity<InvitationResponse> getInvitationStatus(@PathVariable("inviteToken") String token) {
        InvitationResponse response = invitationService.getStatus(token);
        return ResponseEntity.ok(response);
    }

}
