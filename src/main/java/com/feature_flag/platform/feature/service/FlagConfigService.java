package com.feature_flag.platform.feature.service;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.feature.dto.FlagConfigListResponse;
import com.feature_flag.platform.feature.dto.FlagConfigResponse;
import com.feature_flag.platform.feature.dto.RuleResponse;
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.entity.FeatureRule;
import com.feature_flag.platform.feature.entity.FeatureUserOverride;
import com.feature_flag.platform.feature.mapper.FeatureRuleMapper;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRuleRepository;
import com.feature_flag.platform.feature.repository.FeatureUserOverrideRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Serves the targeting configuration array for client SDKs. The platform never evaluates a flag —
 * this service only returns config; the SDK computes on/off. See TARGETING_PLAN.md Phases 3-4.
 */
@Service
@Transactional(readOnly = true)
public class FlagConfigService {

    private final FeatureEnvStatusRepository featureEnvStatusRepository;
    private final FeatureRuleRepository featureRuleRepository;
    private final FeatureUserOverrideRepository featureUserOverrideRepository;
    private final FeatureRuleMapper featureRuleMapper;

    public FlagConfigService(FeatureEnvStatusRepository featureEnvStatusRepository,
                             FeatureRuleRepository featureRuleRepository,
                             FeatureUserOverrideRepository featureUserOverrideRepository,
                             FeatureRuleMapper featureRuleMapper) {
        this.featureEnvStatusRepository = featureEnvStatusRepository;
        this.featureRuleRepository = featureRuleRepository;
        this.featureUserOverrideRepository = featureUserOverrideRepository;
        this.featureRuleMapper = featureRuleMapper;
    }

    public FlagConfigResponse getFlagConfig(String featureKey, String envName, Long tenantId) {
        FeatureEnvStatus fes = featureEnvStatusRepository
                .findByFeature_KeyAndFeature_Tenant_IdAndEnv_Name(featureKey, tenantId, envName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_FEATURE_ENV_STATUS_NOT_FOUND)));

        List<FeatureRule> rules = featureRuleRepository.findAllByFeatureEnvStatusIdOrderByPriorityAsc(fes.getId());
        List<FeatureUserOverride> overrides = featureUserOverrideRepository.findAllByFeatureEnvStatusId(fes.getId());
        return build(fes, rules, overrides);
    }

    public FlagConfigListResponse getAllFlagConfigs(String envName, Long tenantId) {
        List<FeatureEnvStatus> statuses =
                featureEnvStatusRepository.findAllByFeature_Tenant_IdAndEnv_Name(tenantId, envName);
        if (statuses.isEmpty()) {
            return new FlagConfigListResponse(envName, List.of(), Instant.EPOCH);
        }

        List<Long> fesIds = statuses.stream().map(FeatureEnvStatus::getId).toList();
        // Two batch queries total, regardless of flag count, to avoid N+1.
        Map<Long, List<FeatureRule>> rulesByFes = featureRuleRepository
                .findAllByFeatureEnvStatusIdInOrderByPriorityAsc(fesIds)
                .stream()
                .collect(Collectors.groupingBy(r -> r.getFeatureEnvStatus().getId()));
        Map<Long, List<FeatureUserOverride>> overridesByFes = featureUserOverrideRepository
                .findAllByFeatureEnvStatusIdIn(fesIds)
                .stream()
                .collect(Collectors.groupingBy(o -> o.getFeatureEnvStatus().getId()));

        List<FlagConfigResponse> flags = new ArrayList<>();
        Instant listLastModified = Instant.EPOCH;
        for (FeatureEnvStatus fes : statuses) {
            FlagConfigResponse config = build(
                    fes,
                    rulesByFes.getOrDefault(fes.getId(), List.of()),
                    overridesByFes.getOrDefault(fes.getId(), List.of()));
            flags.add(config);
            listLastModified = max(listLastModified, config.lastModified());
        }
        return new FlagConfigListResponse(envName, flags, listLastModified);
    }

    private FlagConfigResponse build(FeatureEnvStatus fes,
                                     List<FeatureRule> rules,
                                     List<FeatureUserOverride> overrides) {
        // Disabled rules are omitted entirely (D8); clients never receive config to ignore.
        List<RuleResponse> ruleResponses = rules.stream()
                .filter(FeatureRule::isEnabled)
                .map(featureRuleMapper::toRuleResponse)
                .toList();

        List<String> enabledUsers = overrides.stream()
                .filter(FeatureUserOverride::isEnabled)
                .map(FeatureUserOverride::getExternalUserId)
                .toList();
        List<String> disabledUsers = overrides.stream()
                .filter(o -> !o.isEnabled())
                .map(FeatureUserOverride::getExternalUserId)
                .toList();

        Instant lastModified = fes.getUpdatedAt();
        lastModified = maxUpdatedAt(lastModified, rules);
        lastModified = maxUpdatedAt(lastModified, overrides);

        return new FlagConfigResponse(
                fes.getFeature().getKey(),
                fes.getEnv().getName(),
                fes.isEnabled(),
                fes.getRolloutPercentage(),
                enabledUsers,
                disabledUsers,
                ruleResponses,
                lastModified);
    }

    private Instant maxUpdatedAt(Instant current, List<? extends com.feature_flag.platform.common.entity.BaseEntity> entities) {
        Instant result = current;
        for (com.feature_flag.platform.common.entity.BaseEntity e : entities) {
            result = max(result, e.getUpdatedAt());
        }
        return result;
    }

    private Instant max(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
