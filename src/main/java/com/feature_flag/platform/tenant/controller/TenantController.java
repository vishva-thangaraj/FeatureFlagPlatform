package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.TenantCreationRequest;
import com.feature_flag.platform.tenant.dto.TenantList;
import com.feature_flag.platform.tenant.dto.TenantResponse;
import com.feature_flag.platform.tenant.dto.UpdateTenantRequest;
import com.feature_flag.platform.tenant.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/{tenantId}")
    public ResponseEntity<TenantResponse> getTenant(@PathVariable Long tenantId,
                                                    @AuthenticationPrincipal TenantUserDetails userDetails) {
        TenantResponse response = tenantService.getTenant(tenantId, userDetails);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{tenantId}")
    public ResponseEntity<TenantResponse> updateTenant(@PathVariable Long tenantId,
                                                       @Valid @RequestBody UpdateTenantRequest request,
                                                       @AuthenticationPrincipal TenantUserDetails userDetails) {
        TenantResponse response = tenantService.updateTenant(tenantId, request, userDetails);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{tenantId}")
    public ResponseEntity<Void> deleteTenant(@PathVariable Long tenantId,
                                             @AuthenticationPrincipal TenantUserDetails userDetails) {
        tenantService.deleteTenant(tenantId, userDetails);
        return ResponseEntity.noContent().build();
    }
}