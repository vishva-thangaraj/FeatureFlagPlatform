package com.feature_flag.platform.feature.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.feature.dto.*;
import com.feature_flag.platform.feature.service.FeatureService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/features")
public class FeatureController {
    private final FeatureService featureService;

    public FeatureController(FeatureService featureService) {
        this.featureService = featureService;
    }

    @PostMapping
    public ResponseEntity<FeatureResponse> createFeature(@Valid @RequestBody CreateFeatureRequest request,
                                                         @AuthenticationPrincipal TenantUserDetails userDetails,
                                                         @RequestParam(required = false) Long tenantID) {
        tenantID = tenantID != null ? tenantID : userDetails.getTenantId();
        FeatureResponse response = featureService.createFeature(request, tenantID);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<FeatureListResponse> getFeatures(@AuthenticationPrincipal TenantUserDetails userDetails,
                                                           @RequestParam(required = false) Long tenantID) {
        tenantID = tenantID != null ? tenantID : userDetails.getTenantId();
        FeatureListResponse response = featureService.getFeaturesList(tenantID);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{featureId}")
    public ResponseEntity<FeatureResponse> getFeature(@PathVariable Long featureId,
                                                      @AuthenticationPrincipal TenantUserDetails userDetails,
                                                      @RequestParam(required = false) Long tenantID) {
        tenantID = tenantID != null ? tenantID : userDetails.getTenantId();
        FeatureResponse response = featureService.getFeature(featureId, tenantID);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{featureId}")
    public ResponseEntity<FeatureResponse> updateFeature(@PathVariable Long featureId,
                                                         @Valid @RequestBody UpdateFeatureRequest request,
                                                         @AuthenticationPrincipal TenantUserDetails userDetails,
                                                         @RequestParam(required = false) Long tenantID) {
        tenantID = tenantID != null ? tenantID : userDetails.getTenantId();
        FeatureResponse response = featureService.updateFeature(featureId, request, tenantID);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{featureId}")
    public ResponseEntity<Void> deleteFeature(@PathVariable Long featureId,
                                              @AuthenticationPrincipal TenantUserDetails userDetails,
                                              @RequestParam(required = false) Long tenantID) {
        tenantID = tenantID != null ? tenantID : userDetails.getTenantId();
        featureService.deleteFeature(featureId, tenantID);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{featureId}/enable")
    public ResponseEntity<Void> enableFeature(@PathVariable Long featureId,@Valid @RequestBody FeatureEnableRequest request,
                                              @AuthenticationPrincipal TenantUserDetails userDetails,
                                              @RequestParam(required = false) Long tenantID) {
        tenantID = tenantID != null ? tenantID : userDetails.getTenantId();
        featureService.setFeatureStatus(featureId, request, tenantID);
        return ResponseEntity.ok().build();
    }
}
