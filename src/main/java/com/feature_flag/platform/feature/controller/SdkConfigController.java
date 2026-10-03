package com.feature_flag.platform.feature.controller;

import com.feature_flag.platform.common.security.ApiKeyPrincipal;
import com.feature_flag.platform.feature.dto.FlagConfigListResponse;
import com.feature_flag.platform.feature.dto.FlagConfigResponse;
import com.feature_flag.platform.feature.service.FlagConfigService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The SDK surface: the same targeting configuration {@link FlagConfigController} serves the dashboard,
 * authenticated by an API key instead of a user's token.
 *
 * <p>There is no {@code envName} parameter. The key is bound to one environment and the tenant is taken
 * from the key, so neither is the caller's to choose — a key shipped inside a customer's application
 * cannot read another environment's configuration by editing a query string. Any {@code envName} sent
 * here is ignored rather than honoured.
 */
@RestController
@RequestMapping("/api/v1/sdk/config")
public class SdkConfigController {

    private final FlagConfigService flagConfigService;

    public SdkConfigController(FlagConfigService flagConfigService) {
        this.flagConfigService = flagConfigService;
    }

    @GetMapping
    public ResponseEntity<FlagConfigListResponse> getAll(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @AuthenticationPrincipal ApiKeyPrincipal key) {
        FlagConfigListResponse response =
                flagConfigService.getAllFlagConfigs(key.getEnvName(), key.getTenantId());
        return withEtag(key, response.lastModified(), ifNoneMatch, response);
    }

    @GetMapping("/{featureKey}")
    public ResponseEntity<FlagConfigResponse> getOne(
            @PathVariable String featureKey,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @AuthenticationPrincipal ApiKeyPrincipal key) {
        FlagConfigResponse response =
                flagConfigService.getFlagConfig(featureKey, key.getEnvName(), key.getTenantId());
        return withEtag(key, response.lastModified(), ifNoneMatch, response);
    }

    /**
     * Conditional requests are what make polling cheap, so they are kept — with the environment in the
     * tag, because this URL does not name it.
     *
     * <p>{@code private} keeps the response out of any shared cache: it is one tenant's unreleased
     * configuration. {@code no-cache} is not "do not store" — it means store it and revalidate before
     * reuse, which is exactly the ETag loop an SDK runs. {@code no-store} would forbid the client cache
     * that makes the 304 worth having. No {@code Vary} on the key header: the right instruction to a
     * shared cache is not to hold this at all, not to key it on a secret.
     */
    private <T> ResponseEntity<T> withEtag(ApiKeyPrincipal key, Instant lastModified,
                                           String ifNoneMatch, T body) {
        String etag = ConfigETag.of(key.getTenantId(), key.getEnvId(), lastModified);
        if (ConfigETag.matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.noCache().cachePrivate())
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(body);
    }
}
