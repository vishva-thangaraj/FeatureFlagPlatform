package com.feature_flag.platform.feature.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.feature.dto.FeatureEnvStatusResponse;
import com.feature_flag.platform.feature.dto.ReplaceRulesRequest;
import com.feature_flag.platform.feature.dto.RuleRequest;
import com.feature_flag.platform.feature.dto.RuleResponse;
import com.feature_flag.platform.feature.service.FeatureService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/features/{featureId}")
public class FeatureRuleController {

    private final FeatureService featureService;

    public FeatureRuleController(FeatureService featureService) {
        this.featureService = featureService;
    }

    @GetMapping("/envs")
    public ResponseEntity<List<FeatureEnvStatusResponse>> listEnvStatuses(
            @PathVariable Long featureId,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        return ResponseEntity.ok(featureService.listEnvStatuses(featureId, userDetails.getTenantId()));
    }

    @GetMapping("/rules/attributes")
    public ResponseEntity<List<String>> listAttributes(
            @PathVariable Long featureId,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        return ResponseEntity.ok(featureService.listAttributes(userDetails.getTenantId()));
    }

    @GetMapping("/envs/{envId}/rules")
    public ResponseEntity<List<RuleResponse>> listRules(
            @PathVariable Long featureId,
            @PathVariable Long envId,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        return ResponseEntity.ok(featureService.listRules(featureId, envId, userDetails.getTenantId()));
    }

    @PostMapping("/envs/{envId}/rules")
    public ResponseEntity<RuleResponse> addRule(
            @PathVariable Long featureId,
            @PathVariable Long envId,
            @Valid @RequestBody RuleRequest request,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        RuleResponse response = featureService.addRule(featureId, envId, request, userDetails.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/envs/{envId}/rules")
    public ResponseEntity<List<RuleResponse>> replaceRules(
            @PathVariable Long featureId,
            @PathVariable Long envId,
            @Valid @RequestBody ReplaceRulesRequest request,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        return ResponseEntity.ok(featureService.replaceRules(featureId, envId, request, userDetails.getTenantId()));
    }

    @PutMapping("/rules/{ruleId}")
    public ResponseEntity<RuleResponse> updateRule(
            @PathVariable Long featureId,
            @PathVariable Long ruleId,
            @Valid @RequestBody RuleRequest request,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        return ResponseEntity.ok(featureService.updateRule(featureId, ruleId, request, userDetails.getTenantId()));
    }

    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @PathVariable Long featureId,
            @PathVariable Long ruleId,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        featureService.deleteRule(featureId, ruleId, userDetails.getTenantId());
        return ResponseEntity.noContent().build();
    }
}
