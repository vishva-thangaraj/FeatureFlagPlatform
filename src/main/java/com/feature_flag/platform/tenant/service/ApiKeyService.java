package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.BadRequestException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.common.security.ApiKeyGenerator;
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.tenant.dto.ApiKeyListResponse;
import com.feature_flag.platform.tenant.dto.ApiKeyResponse;
import com.feature_flag.platform.tenant.dto.CreateApiKeyRequest;
import com.feature_flag.platform.tenant.dto.CreatedApiKeyResponse;
import com.feature_flag.platform.tenant.entity.ApiKey;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.repository.ApiKeyRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Issuing, listing and revoking SDK keys.
 *
 * <p>Every lookup is tenant-scoped in the query rather than by comparing ids after loading a row: each
 * critical found in the security audit was a version of trusting an id the caller supplied.
 */
@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final EnvRepository envRepository;
    private final TenantRepository tenantRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         EnvRepository envRepository,
                         TenantRepository tenantRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.envRepository = envRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Mints a key and returns it in plaintext — the only time it exists outside the caller's hands.
     * What is stored is its digest and its leading characters.
     */
    @Transactional
    public CreatedApiKeyResponse createApiKey(CreateApiKeyRequest request, Long tenantId) {
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new BadRequestException(I18N.getMessage(Messages.API_KEY_EXPIRY_IN_PAST));
        }

        Env env = envRepository.findByNameAndTenantId(request.envName(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_ENV_NOT_FOUND)));
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_TENANT_NOT_FOUND)));

        ApiKeyGenerator.GeneratedKey generated = ApiKeyGenerator.generate();
        ApiKey saved = apiKeyRepository.save(ApiKey.builder()
                .tenant(tenant)
                .env(env)
                .name(request.name())
                .keyHash(generated.keyHash())
                .prefix(generated.prefix())
                .expiresAt(request.expiresAt())
                .build());

        return new CreatedApiKeyResponse(toResponse(saved), generated.plaintext());
    }

    @Transactional(readOnly = true)
    public ApiKeyListResponse listApiKeys(Long tenantId) {
        List<ApiKeyResponse> keys = apiKeyRepository.findByTenantIdWithEnv(tenantId).stream()
                .map(this::toResponse)
                .toList();
        return new ApiKeyListResponse(keys);
    }

    /**
     * Revoking is idempotent: the caller's goal is that the key stops working, and it already has.
     * Treating a second call as an error would make a retried or duplicated revocation look like a
     * failure, which is the wrong thing to report about a credential that is already dead.
     */
    @Transactional
    public void revokeApiKey(Long apiKeyId, Long tenantId) {
        ApiKey apiKey = apiKeyRepository.findByIdAndTenantId(apiKeyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_API_KEY_NOT_FOUND)));

        if (apiKey.getRevokedAt() == null) {
            apiKey.setRevokedAt(Instant.now());
            apiKeyRepository.save(apiKey);
        }
    }

    private ApiKeyResponse toResponse(ApiKey apiKey) {
        return new ApiKeyResponse(
                apiKey.getId().toString(),
                apiKey.getName(),
                apiKey.getEnv().getName(),
                apiKey.getPrefix(),
                apiKey.getCreatedAt(),
                apiKey.getLastUsedAt(),
                apiKey.getExpiresAt(),
                apiKey.getRevokedAt());
    }
}
