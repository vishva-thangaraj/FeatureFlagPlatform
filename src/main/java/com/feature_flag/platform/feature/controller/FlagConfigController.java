package com.feature_flag.platform.feature.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.common.security.filter.TenantContextFilter;
import com.feature_flag.platform.feature.dto.FlagConfigListResponse;
import com.feature_flag.platform.feature.dto.FlagConfigResponse;
import com.feature_flag.platform.feature.service.FlagConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Client-facing endpoints. Returns the flag/targeting array for an environment; the SDK evaluates.
 * Keyed by feature key + env name (not TSIDs) so SDKs need not know internal ids (D10).
 *
 * <p>NOTE: these currently sit behind the dashboard user JWT. API-key auth is required before this
 * is usable by a real SDK — see TARGETING_PLAN.md Phase 3 "Authentication gap".
 */
@RestController
@RequestMapping("/api/v1/config")
public class FlagConfigController {

    private final FlagConfigService flagConfigService;

    public FlagConfigController(FlagConfigService flagConfigService) {
        this.flagConfigService = flagConfigService;
    }

    @GetMapping
    public ResponseEntity<FlagConfigListResponse> getAll(
            @RequestParam String envName,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        Long tenantId = userDetails.getTenantId();
        FlagConfigListResponse response = flagConfigService.getAllFlagConfigs(envName, tenantId);
        return withEtag(tenantId, response.lastModified(), ifNoneMatch, response);
    }

    @GetMapping("/{featureKey}")
    public ResponseEntity<FlagConfigResponse> getOne(
            @PathVariable String featureKey,
            @RequestParam String envName,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        Long tenantId = userDetails.getTenantId();
        FlagConfigResponse response = flagConfigService.getFlagConfig(featureKey, envName, tenantId);
        return withEtag(tenantId, response.lastModified(), ifNoneMatch, response);
    }

    /**
     * The tenant is part of the ETag, and {@code Vary} names the tenant header: the same URL now returns
     * different bodies per tenant, so an ETag derived from the timestamp alone would collide across
     * tenants and any shared cache would need to key on the header.
     */
    private <T> ResponseEntity<T> withEtag(Long tenantId, Instant lastModified, String ifNoneMatch, T body) {
        String etag = "\"" + tenantId + "-" + (lastModified == null ? 0L : lastModified.toEpochMilli()) + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .header(HttpHeaders.VARY, TenantContextFilter.TENANT_HEADER)
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .header(HttpHeaders.VARY, TenantContextFilter.TENANT_HEADER)
                .body(body);
    }
}
