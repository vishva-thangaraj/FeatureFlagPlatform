package com.feature_flag.platform.feature.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.feature.dto.FeatureUserOverrideResponse;
import com.feature_flag.platform.feature.dto.OverrideRequest;
import com.feature_flag.platform.feature.service.FeatureService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/features/{featureId}/envs/{envId}/overrides")
public class FeatureOverrideController {

    private final FeatureService featureService;

    public FeatureOverrideController(FeatureService featureService) {
        this.featureService = featureService;
    }

    @GetMapping
    public ResponseEntity<List<FeatureUserOverrideResponse>> listOverrides(
            @PathVariable Long featureId,
            @PathVariable Long envId,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        return ResponseEntity.ok(featureService.listOverrides(featureId, envId, userDetails.getTenantId()));
    }

    // Upsert. externalUserId is in the body, not the path — customer-supplied ids are often
    // emails, which URL-encode badly.
    @PostMapping
    public ResponseEntity<FeatureUserOverrideResponse> upsertOverride(
            @PathVariable Long featureId,
            @PathVariable Long envId,
            @Valid @RequestBody OverrideRequest request,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        FeatureUserOverrideResponse response =
                featureService.upsertOverride(featureId, envId, request, userDetails.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{externalUserId}")
    public ResponseEntity<Void> deleteOverride(
            @PathVariable Long featureId,
            @PathVariable Long envId,
            @PathVariable String externalUserId,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        featureService.deleteOverride(featureId, envId, externalUserId, userDetails.getTenantId());
        return ResponseEntity.noContent().build();
    }
}
