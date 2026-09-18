package com.feature_flag.platform.feature.service;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.feature.dto.FlagConfigListResponse;
import com.feature_flag.platform.feature.dto.FlagConfigResponse;
import com.feature_flag.platform.feature.dto.RuleResponse;
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.entity.FeatureRule;
import com.feature_flag.platform.feature.entity.FeatureUserOverride;
import com.feature_flag.platform.feature.mapper.FeatureRuleMapper;
import com.feature_flag.platform.feature.repository.EnvRepository;
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
 * this service only returns config; the SDK computes on/off.
 */
@Service
@Transactional(readOnly = true)
public class FlagConfigService {

    private final FeatureEnvStatusRepository featureEnvStatusRepository;
    private final FeatureRuleRepository featureRuleRepository;
    private final FeatureUserOverrideRepository featureUserOverrideRepository;
    private final EnvRepository envRepository;
    private final FeatureRuleMapper featureRuleMapper;

    public FlagConfigService(FeatureEnvStatusRepository featureEnvStatusRepository,
                             FeatureRuleRepository featureRuleRepository,
                             FeatureUserOverrideRepository featureUserOverrideRepository,
                             EnvRepository envRepository,
                             FeatureRuleMapper featureRuleMapper) {
        this.featureEnvStatusRepository = featureEnvStatusRepository;
        this.featureRuleRepository = featureRuleRepository;
        this.featureUserOverrideRepository = featureUserOverrideRepository;
        this.envRepository = envRepository;
        this.featureRuleMapper = featureRuleMapper;
    }

    public FlagConfigResponse getFlagConfig(String featureKey, String envName, Long tenantId) {
        // findConfig, not the derived finder: it JOIN FETCHes feature and env, which build() reads.
        FeatureEnvStatus fes = featureEnvStatusRepository
                .findConfig(featureKey, tenantId, envName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_FEATURE_ENV_STATUS_NOT_FOUND)));

        List<FeatureRule> rules = featureRuleRepository.findAllByFeatureEnvStatusIdOrderByPriorityAsc(fes.getId());
        List<FeatureUserOverride> overrides = featureUserOverrideRepository.findAllByFeatureEnvStatusId(fes.getId());
        return build(fes, rules, overrides);
    }

    /**
     * All flags for one environment.
     *
     * <p>The environment is resolved first and a missing one is a 404. Returning {@code 200} with an
     * empty array for an unknown {@code envName} — a typo, or an env that has been deleted — told
     * every SDK that the tenant has no flags at all, so each one silently fell back to its built-in
     * defaults: a total targeting outage that looks perfectly healthy from the outside. The
     * single-flag endpoint has always 404'd; the two now agree.
     *
     * <p>Four queries, regardless of how many flags the environment holds: the env, the status rows
     * (with feature and env fetched in the same select), then one batch of rules and one of overrides.
     */
    public FlagConfigListResponse getAllFlagConfigs(String envName, Long tenantId) {
        Env env = envRepository.findByNameAndTenantId(envName, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_ENV_NOT_FOUND)));

        List<FeatureEnvStatus> statuses = featureEnvStatusRepository.findAllConfigs(tenantId, envName);

        // The environment's own timestamp is the floor for the whole list. Without it, deleting a
        // feature that was not the most recently touched one would leave the maximum where it was:
        // the payload loses a flag while the ETag stays put, and a polling SDK gets 304 forever.
        Instant listLastModified = env.getUpdatedAt() == null ? Instant.EPOCH : env.getUpdatedAt();
        if (statuses.isEmpty()) {
            return new FlagConfigListResponse(envName, List.of(), listLastModified);
        }

        List<Long> fesIds = statuses.stream().map(FeatureEnvStatus::getId).toList();
        Map<Long, List<FeatureRule>> rulesByFes = featureRuleRepository
                .findAllByFeatureEnvStatusIdInOrderByPriorityAsc(fesIds)
                .stream()
                .collect(Collectors.groupingBy(r -> r.getFeatureEnvStatus().getId()));
        Map<Long, List<FeatureUserOverride>> overridesByFes = featureUserOverrideRepository
                .findAllByFeatureEnvStatusIdIn(fesIds)
                .stream()
                .collect(Collectors.groupingBy(o -> o.getFeatureEnvStatus().getId()));

        List<FlagConfigResponse> flags = new ArrayList<>();
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

        // The maximum only ever moves forward over surviving rows, so a deleted rule or override
        // could not show up here on its own. FeatureService stamps the status row whenever one of
        // its children changes, which is what makes a deletion visible to a conditional request.
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
