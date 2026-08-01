package com.feature_flag.platform.feature.service;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ConflictException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.feature.dto.*;
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.entity.Feature;
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.entity.FeatureRule;
import com.feature_flag.platform.feature.entity.FeatureUserOverride;
import com.feature_flag.platform.feature.mapper.FeatureEnvStatusMapper;
import com.feature_flag.platform.feature.mapper.FeatureMapper;
import com.feature_flag.platform.feature.mapper.FeatureRuleMapper;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.feature.repository.FeatureRuleRepository;
import com.feature_flag.platform.feature.repository.FeatureUserOverrideRepository;
import com.feature_flag.platform.feature.validation.RuleValidator;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class FeatureService {
    private final FeatureRepository featureRepository;
    private final TenantRepository tenantRepository;
    private final EnvRepository envRepository;
    private final FeatureEnvStatusRepository featureEnvStatusRepository;
    private final FeatureRuleRepository featureRuleRepository;
    private final FeatureUserOverrideRepository featureUserOverrideRepository;
    private final FeatureEnvStatusMapper featureEnvStatusMapper;
    private final FeatureRuleMapper featureRuleMapper;
    private final RuleValidator ruleValidator;

    public FeatureService(FeatureRepository featureRepository,
                          TenantRepository tenantRepository,
                          EnvRepository envRepository,
                          FeatureEnvStatusRepository featureEnvStatusRepository,
                          FeatureRuleRepository featureRuleRepository,
                          FeatureUserOverrideRepository featureUserOverrideRepository,
                          FeatureEnvStatusMapper featureEnvStatusMapper,
                          FeatureRuleMapper featureRuleMapper,
                          RuleValidator ruleValidator) {
        this.featureRepository = featureRepository;
        this.tenantRepository = tenantRepository;
        this.envRepository = envRepository;
        this.featureEnvStatusRepository = featureEnvStatusRepository;
        this.featureRuleRepository = featureRuleRepository;
        this.featureUserOverrideRepository = featureUserOverrideRepository;
        this.featureEnvStatusMapper = featureEnvStatusMapper;
        this.featureRuleMapper = featureRuleMapper;
        this.ruleValidator = ruleValidator;
    }

    @Transactional
    public FeatureResponse createFeature(CreateFeatureRequest request, Long tenantId) {
        if (featureRepository.existsByKeyAndTenantId(request.key(), tenantId)) {
            throw new ConflictException(I18N.getMessage(Messages.FEATURE_KEY_ALREADY_EXISTS));
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_TENANT_NOT_FOUND)));

        Feature feature = Feature.builder()
                .key(request.key())
                .description(request.description())
                .tenant(tenant)
                .build();
        feature.setCreatedAt(LocalDateTime.now().toInstant(java.time.ZoneOffset.UTC));

        featureRepository.save(feature);
        return FeatureMapper.toFeatureResponse(feature);
    }

    public FeatureListResponse getFeaturesList(Long tenantId) {
        List<Feature> features = featureRepository.findAllByTenantId(tenantId);
        List<FeatureResponse> featureResponses = features.stream()
                .map(FeatureMapper::toFeatureResponse)
                .toList();
        return new FeatureListResponse(featureResponses);
    }

    public FeatureResponse getFeature(Long featureId, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);
        return FeatureMapper.toFeatureResponse(feature);
    }

    @Transactional
    public FeatureResponse updateFeature(Long featureId, UpdateFeatureRequest request, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);
        feature.setDescription(request.description());
        featureRepository.save(feature);
        return FeatureMapper.toFeatureResponse(feature);
    }

    @Transactional
    public void deleteFeature(Long featureId, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);
        // No inverse @OneToMany collections exist on Feature/FeatureEnvStatus, so children are
        // removed explicitly to avoid an FK violation on feature_env_status.feature_id.
        List<FeatureEnvStatus> statuses = featureEnvStatusRepository.findAllByFeatureId(featureId);
        for (FeatureEnvStatus status : statuses) {
            featureRuleRepository.deleteAllByFeatureEnvStatusId(status.getId());
            featureUserOverrideRepository.deleteAllByFeatureEnvStatusId(status.getId());
        }
        featureEnvStatusRepository.deleteAll(statuses);
        featureRepository.delete(feature);
    }

    private Feature findByIdAndTenant(Long featureId, Long tenantId) {
        return featureRepository.findById(featureId)
                .filter(f -> f.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_FEATURE_NOT_FOUND)));
    }

    @Transactional
    public void setFeatureStatus(Long featureId, @Valid FeatureEnableRequest request, Long tenantId) {
        Feature feature =findByIdAndTenant(featureId, tenantId);

        Long envID = request.envId();

        Env env = envRepository.findById(envID)
                .filter(e -> e.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_ENV_NOT_FOUND)));

        FeatureEnvStatus featureEnvStatus = featureEnvStatusRepository.findByFeatureIdAndEnvId(featureId, envID)
                .orElseGet(() -> FeatureEnvStatus.builder()
                        .feature(feature)
                        .env(env)
                        .build());
        featureEnvStatus.setEnabled(request.enabled());
        if (request.rolloutPercentage() != null) {
            featureEnvStatus.setRolloutPercentage(request.rolloutPercentage());
        }
        featureEnvStatusRepository.save(featureEnvStatus);
    }

    // ---------- Feature env status listing ----------

    public List<FeatureEnvStatusResponse> listEnvStatuses(Long featureId, Long tenantId) {
        findByIdAndTenant(featureId, tenantId);
        return featureEnvStatusRepository.findAllByFeatureId(featureId)
                .stream()
                .map(featureEnvStatusMapper::toFeatureEnvStatusResponse)
                .toList();
    }

    // ---------- Feature rules ----------

    public List<RuleResponse> listRules(Long featureId, Long envId, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);
        return featureRuleRepository.findAllByFeatureEnvStatusIdOrderByPriorityAsc(fes.getId())
                .stream()
                .map(featureRuleMapper::toRuleResponse)
                .toList();
    }

    public List<String> listAttributes(Long tenantId) {
        return featureRuleRepository.findDistinctAttributesByTenantId(tenantId);
    }

    @Transactional
    public RuleResponse addRule(Long featureId, Long envId, RuleRequest request, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);

        // Validate the candidate against the existing set so a new rule cannot create a
        // contradictory (attribute, operator) pair (TARGETING_PLAN.md §2).
        List<RuleRequest> combined = new ArrayList<>();
        for (FeatureRule existing : featureRuleRepository.findAllByFeatureEnvStatusIdOrderByPriorityAsc(fes.getId())) {
            combined.add(featureRuleMapper.toRuleRequest(existing));
        }
        combined.add(request);
        ruleValidator.validateSet(combined);

        FeatureRule rule = FeatureRule.builder()
                .featureEnvStatus(fes)
                .attribute(request.attribute())
                .operator(request.operator())
                .valueType(request.valueType())
                .valueJson(featureRuleMapper.encodeValues(request.values()))
                .caseSensitive(request.caseSensitive())
                .negated(request.negated())
                .enabled(request.enabled())
                .priority(request.priority())
                .build();
        return featureRuleMapper.toRuleResponse(featureRuleRepository.save(rule));
    }

    @Transactional
    public RuleResponse updateRule(Long featureId, Long ruleId, RuleRequest request, Long tenantId) {
        FeatureRule rule = findRuleForTenant(featureId, ruleId, tenantId);

        // Re-validate the set with this rule's new values substituted in.
        List<RuleRequest> combined = new ArrayList<>();
        combined.add(request);
        for (FeatureRule existing :
                featureRuleRepository.findAllByFeatureEnvStatusIdOrderByPriorityAsc(rule.getFeatureEnvStatus().getId())) {
            if (!existing.getId().equals(ruleId)) {
                combined.add(featureRuleMapper.toRuleRequest(existing));
            }
        }
        ruleValidator.validateSet(combined);

        rule.setAttribute(request.attribute());
        rule.setOperator(request.operator());
        rule.setValueType(request.valueType());
        rule.setValueJson(featureRuleMapper.encodeValues(request.values()));
        rule.setCaseSensitive(request.caseSensitive());
        rule.setNegated(request.negated());
        rule.setEnabled(request.enabled());
        rule.setPriority(request.priority());
        return featureRuleMapper.toRuleResponse(featureRuleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Long featureId, Long ruleId, Long tenantId) {
        FeatureRule rule = findRuleForTenant(featureId, ruleId, tenantId);
        featureRuleRepository.delete(rule);
    }

    /** Replace the entire rule set for a feature/env atomically. An empty list clears all rules. */
    @Transactional
    public List<RuleResponse> replaceRules(Long featureId, Long envId, ReplaceRulesRequest request, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);
        ruleValidator.validateSet(request.rules());

        featureRuleRepository.deleteAllByFeatureEnvStatusId(fes.getId());
        List<RuleResponse> saved = new ArrayList<>();
        for (RuleRequest r : request.rules()) {
            FeatureRule rule = FeatureRule.builder()
                    .featureEnvStatus(fes)
                    .attribute(r.attribute())
                    .operator(r.operator())
                    .valueType(r.valueType())
                    .valueJson(featureRuleMapper.encodeValues(r.values()))
                    .caseSensitive(r.caseSensitive())
                    .negated(r.negated())
                    .enabled(r.enabled())
                    .priority(r.priority())
                    .build();
            saved.add(featureRuleMapper.toRuleResponse(featureRuleRepository.save(rule)));
        }
        return saved;
    }

    // ---------- User overrides ----------

    public List<FeatureUserOverrideResponse> listOverrides(Long featureId, Long envId, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);
        return featureUserOverrideRepository.findAllByFeatureEnvStatusId(fes.getId())
                .stream()
                .map(featureEnvStatusMapper::toFeatureUserOverrideResponse)
                .toList();
    }

    @Transactional
    public FeatureUserOverrideResponse upsertOverride(Long featureId, Long envId, OverrideRequest request, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);
        FeatureUserOverride override = featureUserOverrideRepository
                .findByFeatureEnvStatusIdAndExternalUserId(fes.getId(), request.externalUserId())
                .orElseGet(() -> FeatureUserOverride.builder()
                        .featureEnvStatus(fes)
                        .externalUserId(request.externalUserId())
                        .build());
        override.setEnabled(request.enabled());
        return featureEnvStatusMapper.toFeatureUserOverrideResponse(featureUserOverrideRepository.save(override));
    }

    @Transactional
    public void deleteOverride(Long featureId, Long envId, String externalUserId, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);
        FeatureUserOverride override = featureUserOverrideRepository
                .findByFeatureEnvStatusIdAndExternalUserId(fes.getId(), externalUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_FEATURE_OVERRIDE_NOT_FOUND)));
        featureUserOverrideRepository.delete(override);
    }

    // ---------- Helpers ----------

    private FeatureEnvStatus findFeatureEnvStatus(Long featureId, Long envId, Long tenantId) {
        findByIdAndTenant(featureId, tenantId);
        return featureEnvStatusRepository.findByFeatureIdAndEnvId(featureId, envId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_FEATURE_ENV_STATUS_NOT_FOUND)));
    }

    private FeatureRule findRuleForTenant(Long featureId, Long ruleId, Long tenantId) {
        findByIdAndTenant(featureId, tenantId);
        FeatureRule rule = featureRuleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_FEATURE_RULE_NOT_FOUND)));
        if (!rule.getFeatureEnvStatus().getFeature().getId().equals(featureId)) {
            throw new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_FEATURE_RULE_NOT_FOUND));
        }
        return rule;
    }
}