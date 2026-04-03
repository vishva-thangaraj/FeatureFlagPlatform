package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.TenantCreationRequest;
import com.feature_flag.platform.tenant.dto.TenantList;
import com.feature_flag.platform.tenant.dto.TenantResponse;
import com.feature_flag.platform.tenant.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody TenantCreationRequest tenant,
                                                        @AuthenticationPrincipal TenantUserDetails userDetails) {
        TenantResponse response = tenantService.createTenant(tenant, userDetails);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<TenantList> getTenants(@AuthenticationPrincipal TenantUserDetails userDetails) {
        TenantList response = tenantService.getTenantsForUser(userDetails);
        return ResponseEntity.ok(response);
    }
}