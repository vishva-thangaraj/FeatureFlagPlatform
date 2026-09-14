package com.feature_flag.platform.feature.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.feature.dto.CreateEnvRequest;
import com.feature_flag.platform.feature.dto.EnvListResponse;
import com.feature_flag.platform.feature.dto.EnvResponse;
import com.feature_flag.platform.feature.dto.UpdateEnvRequest;
import com.feature_flag.platform.feature.service.ENVService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * The acting tenant comes from the request context that TenantContextFilter resolved (the
 * {@code X-Tenant-Id} header, or the token's default tenant) and is already authorized against an ACTIVE
 * membership. It is never taken from a client-supplied parameter.
 */
@RestController
@RequestMapping("/api/v1/envs")
public class ENVController {
    private final ENVService envService;

    public ENVController(ENVService envService) {
        this.envService = envService;
    }

    @PostMapping
    public ResponseEntity<EnvResponse> createEnv(@Valid @RequestBody CreateEnvRequest request,
                                                 @AuthenticationPrincipal TenantUserDetails userDetails) {
        EnvResponse response = envService.createEnv(request, userDetails.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<EnvListResponse> getEnvs(@AuthenticationPrincipal TenantUserDetails userDetails) {
        EnvListResponse response = envService.getEnvsList(userDetails.getTenantId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{envId}")
    public ResponseEntity<EnvResponse> getEnv(@PathVariable Long envId,
                                              @AuthenticationPrincipal TenantUserDetails userDetails) {
        EnvResponse response = envService.getEnv(envId, userDetails.getTenantId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{envId}")
    public ResponseEntity<EnvResponse> updateEnv(@PathVariable Long envId,
                                                 @Valid @RequestBody UpdateEnvRequest request,
                                                 @AuthenticationPrincipal TenantUserDetails userDetails) {
        EnvResponse response = envService.updateEnv(envId, request, userDetails.getTenantId());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{envId}")
    public ResponseEntity<Void> deleteEnv(@PathVariable Long envId,
                                          @AuthenticationPrincipal TenantUserDetails userDetails) {
        envService.deleteEnv(envId, userDetails.getTenantId());
        return ResponseEntity.noContent().build();
    }
}
