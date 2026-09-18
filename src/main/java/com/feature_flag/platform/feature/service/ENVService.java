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
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.mapper.EnvMapper;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRuleRepository;
import com.feature_flag.platform.feature.repository.FeatureUserOverrideRepository;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ENVService {
    private final EnvRepository envRepository;
    private final TenantRepository tenantRepository;
    private final FeatureEnvStatusRepository featureEnvStatusRepository;
    private final FeatureRuleRepository featureRuleRepository;
    private final FeatureUserOverrideRepository featureUserOverrideRepository;

    public ENVService(EnvRepository envRepository,
                      TenantRepository tenantRepository,
                      FeatureEnvStatusRepository featureEnvStatusRepository,
                      FeatureRuleRepository featureRuleRepository,
                      FeatureUserOverrideRepository featureUserOverrideRepository) {
        this.envRepository = envRepository;
        this.tenantRepository = tenantRepository;
        this.featureEnvStatusRepository = featureEnvStatusRepository;
        this.featureRuleRepository = featureRuleRepository;
        this.featureUserOverrideRepository = featureUserOverrideRepository;
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

    @Transactional(readOnly = true)
    public EnvListResponse getEnvsList(Long tenantId) {
        List<Env> envs = envRepository.findAllByTenantId(tenantId);
        List<EnvResponse> envResponses = envs.stream()
                .map(EnvMapper::toEnvResponse)
                .toList();
        return new EnvListResponse(envResponses);
    }

    @Transactional(readOnly = true)
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

    /**
     * Delete an environment and everything configured inside it.
     *
     * <p>{@code FeatureEnvStatus} maps {@code env} from the child side only — no inverse
     * {@code @OneToMany}, no cascade, no orphan removal — so deleting the row directly hit an FK
     * violation the moment any flag had ever been toggled in this environment. Wave 1 turned that
     * from a 500 into a 409, which is a better status for the wrong answer: nothing the caller can do
     * resolves it, because no endpoint exists to detach a status row from its env.
     *
     * <p>So this cascades, the way {@code FeatureService.deleteFeature} already does for the other
     * parent of the same table. Refusing instead would have made {@code DELETE /api/v1/envs/{id}}
     * unusable in practice — every environment that has been used at all has status rows — and the
     * rows being removed are per-environment configuration that is meaningless without the
     * environment. The features themselves are untouched; only their configuration <em>in this
     * environment</em> goes. Clients polling {@code /api/v1/config?envName=} for the deleted name now
     * get 404 rather than a silently empty flag list.
     */
    @Transactional
    public void deleteEnv(Long envId, Long tenantId) {
        Env env = findByIdAndTenant(envId, tenantId);

        List<FeatureEnvStatus> statuses = featureEnvStatusRepository.findAllByEnvId(envId);
        for (FeatureEnvStatus status : statuses) {
            featureRuleRepository.deleteAllByFeatureEnvStatusId(status.getId());
            featureUserOverrideRepository.deleteAllByFeatureEnvStatusId(status.getId());
        }
        featureEnvStatusRepository.deleteAll(statuses);
        envRepository.delete(env);
    }

    private Env findByIdAndTenant(Long envId, Long tenantId) {
        return envRepository.findById(envId)
                .filter(e -> e.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_ENV_NOT_FOUND)));
    }
}
