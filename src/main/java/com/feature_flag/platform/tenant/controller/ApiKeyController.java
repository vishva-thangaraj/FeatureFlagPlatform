package com.feature_flag.platform.tenant.controller;

import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.tenant.dto.ApiKeyListResponse;
import com.feature_flag.platform.tenant.dto.CreateApiKeyRequest;
import com.feature_flag.platform.tenant.dto.CreatedApiKeyResponse;
import com.feature_flag.platform.tenant.service.ApiKeyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Key management for the dashboard, authenticated by the user's token. The keys themselves are used
 * against {@code /api/v1/sdk/}, which this surface has nothing to do with.
 */
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /** The response body carries the key in plaintext, once. It cannot be retrieved again. */
    @PostMapping
    public ResponseEntity<CreatedApiKeyResponse> createApiKey(
            @Valid @RequestBody CreateApiKeyRequest request,
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        CreatedApiKeyResponse response = apiKeyService.createApiKey(request, userDetails.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<ApiKeyListResponse> listApiKeys(
            @AuthenticationPrincipal TenantUserDetails userDetails) {
        return ResponseEntity.ok(apiKeyService.listApiKeys(userDetails.getTenantId()));
    }

    @DeleteMapping("/{apiKeyId}")
    public ResponseEntity<Void> revokeApiKey(@PathVariable Long apiKeyId,
                                             @AuthenticationPrincipal TenantUserDetails userDetails) {
        apiKeyService.revokeApiKey(apiKeyId, userDetails.getTenantId());
        return ResponseEntity.noContent().build();
    }
}
