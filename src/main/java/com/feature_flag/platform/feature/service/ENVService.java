package com.feature_flag.platform.feature.service;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ConflictException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.feature.dto.CreateEnvRequest;
import com.feature_flag.platform.feature.dto.EnvListResponse;
import com.feature_flag.platform.feature.dto.EnvResponse;
import com.feature_flag.platform.feature.dto.UpdateEnvRequest;
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.mapper.EnvMapper;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ENVService {
    private final EnvRepository envRepository;
    private final TenantRepository tenantRepository;

    public ENVService(EnvRepository envRepository, TenantRepository tenantRepository) {
        this.envRepository = envRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public EnvResponse createEnv(CreateEnvRequest request, Long tenantId) {
        if (envRepository.existsByNameAndTenantId(request.name(), tenantId)) {
            throw new ConflictException(I18N.getMessage(Messages.ENV_NAME_ALREADY_EXISTS));
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_TENANT_NOT_FOUND)));

        Env env = Env.builder()
                .name(request.name())
                .description(request.description())
                .tenant(tenant)
                .build();

        env = envRepository.save(env);
        return EnvMapper.toEnvResponse(env);
    }

    public EnvListResponse getEnvsList(Long tenantId) {
        List<Env> envs = envRepository.findAllByTenantId(tenantId);
        List<EnvResponse> envResponses = envs.stream()
                .map(EnvMapper::toEnvResponse)
                .toList();
        return new EnvListResponse(envResponses);
    }

    public EnvResponse getEnv(Long envId, Long tenantId) {
        Env env = findByIdAndTenant(envId, tenantId);
        return EnvMapper.toEnvResponse(env);
    }

    @Transactional
    public EnvResponse updateEnv(Long envId, UpdateEnvRequest request, Long tenantId) {
        Env env = findByIdAndTenant(envId, tenantId);

        if (request.name() != null) {
            if (envRepository.existsByNameAndTenantId(request.name(), tenantId)
                    && !env.getName().equals(request.name())) {
                throw new ConflictException(I18N.getMessage(Messages.ENV_NAME_ALREADY_EXISTS));
            }
            env.setName(request.name());
        }
        if (request.description() != null) {
            env.setDescription(request.description());
        }

        envRepository.save(env);
        return EnvMapper.toEnvResponse(env);
    }

    @Transactional
    public void deleteEnv(Long envId, Long tenantId) {
        Env env = findByIdAndTenant(envId, tenantId);
        envRepository.delete(env);
    }

    private Env findByIdAndTenant(Long envId, Long tenantId) {
        return envRepository.findById(envId)
                .filter(e -> e.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_ENV_NOT_FOUND)));
    }
}
