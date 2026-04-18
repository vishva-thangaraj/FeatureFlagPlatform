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
                                                         @AuthenticationPrincipal TenantUserDetails userDetails) {
        FeatureResponse response = featureService.createFeature(request, userDetails.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<FeatureListResponse> getFeatures(@AuthenticationPrincipal TenantUserDetails userDetails) {
        FeatureListResponse response = featureService.getFeaturesList(userDetails.getTenantId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{featureId}")
    public ResponseEntity<FeatureResponse> getFeature(@PathVariable Long featureId,
                                                      @AuthenticationPrincipal TenantUserDetails userDetails) {
        FeatureResponse response = featureService.getFeature(featureId, userDetails.getTenantId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{featureId}")
    public ResponseEntity<FeatureResponse> updateFeature(@PathVariable Long featureId,
                                                         @Valid @RequestBody UpdateFeatureRequest request,
                                                         @AuthenticationPrincipal TenantUserDetails userDetails) {
        FeatureResponse response = featureService.updateFeature(featureId, request, userDetails.getTenantId());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{featureId}")
    public ResponseEntity<Void> deleteFeature(@PathVariable Long featureId,
                                              @AuthenticationPrincipal TenantUserDetails userDetails) {
        featureService.deleteFeature(featureId, userDetails.getTenantId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{featureId}/enable")
    public ResponseEntity<Void> enableFeature(@PathVariable Long featureId,@Valid @RequestBody FeatureEnableRequest request,
                                              @AuthenticationPrincipal TenantUserDetails userDetails) {
        featureService.setFeatureStatus(featureId, request, userDetails.getTenantId());
        return ResponseEntity.ok().build();
    }
}
