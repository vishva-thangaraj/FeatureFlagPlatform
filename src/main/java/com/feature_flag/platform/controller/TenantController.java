package com.feature_flag.platform.controller;


import com.feature_flag.platform.dto.tenant.TenantCreationRequest;
import com.feature_flag.platform.dto.tenant.TenantList;
import com.feature_flag.platform.dto.tenant.TenantResponse;
import com.feature_flag.platform.dto.tenant.UserInviteRequest;
import com.feature_flag.platform.security.TenantUserDetails;
import com.feature_flag.platform.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody TenantCreationRequest tenant, @AuthenticationPrincipal TenantUserDetails userDetails) {
        TenantResponse response = tenantService.createTenant(tenant, userDetails);

        return ResponseEntity.status(HttpStatusCode.valueOf(201)).body(response);
    }

    @PostMapping("/invite")
    public ResponseEntity<Void> inviteUserToTenant(@Valid @RequestBody UserInviteRequest userInviteRequest, @AuthenticationPrincipal TenantUserDetails userDetails) {
        tenantService.inviteUserToTenant(userDetails, userInviteRequest.email(), userInviteRequest.role());

        return ResponseEntity.ok().build();

    }

    @GetMapping
    public ResponseEntity<TenantList> getTenants(@AuthenticationPrincipal TenantUserDetails userDetails) {
        TenantList response = tenantService.getTenantsForUser(userDetails);

        return ResponseEntity.ok(response);
    }
}
