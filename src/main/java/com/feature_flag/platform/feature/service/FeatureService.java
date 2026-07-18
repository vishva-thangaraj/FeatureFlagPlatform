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
import com.feature_flag.platform.feature.enums.RuleOperator;
import com.feature_flag.platform.feature.mapper.FeatureMapper;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.feature.repository.FeatureRuleRepository;
import com.feature_flag.platform.feature.repository.FeatureUserOverrideRepository;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FeatureService {
    private final FeatureRepository featureRepository;
    private final TenantRepository tenantRepository;
    private final EnvRepository envRepository;
    private final FeatureEnvStatusRepository featureEnvStatusRepository;
    private final FeatureRuleRepository featureRuleRepository;
    private final FeatureUserOverrideRepository featureUserOverrideRepository;
    private final ObjectMapper objectMapper;

    public FeatureService(FeatureRepository featureRepository,
                          TenantRepository tenantRepository,
                          EnvRepository envRepository,
                          FeatureEnvStatusRepository featureEnvStatusRepository,
                          FeatureRuleRepository featureRuleRepository,
                          FeatureUserOverrideRepository featureUserOverrideRepository,
                          ObjectMapper objectMapper) {
        this.featureRepository = featureRepository;
        this.tenantRepository = tenantRepository;
        this.envRepository = envRepository;
        this.featureEnvStatusRepository = featureEnvStatusRepository;
        this.featureRuleRepository = featureRuleRepository;
        this.featureUserOverrideRepository = featureUserOverrideRepository;
        this.objectMapper = objectMapper;
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

    // ---------- Feature rules ----------

    @Transactional
    public FeatureRule addRule(Long featureId, Long envId, String attribute,
                               RuleOperator operator, Object value, int priority, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);
        FeatureRule rule = FeatureRule.builder()
                .featureEnvStatus(fes)
                .attribute(attribute)
                .operator(operator)
                .valueJson(encodeValue(value))
                .priority(priority)
                .build();
        return featureRuleRepository.save(rule);
    }

    @Transactional
    public FeatureRule updateRule(Long featureId, Long ruleId, String attribute,
                                  RuleOperator operator, Object value, int priority, Long tenantId) {
        FeatureRule rule = findRuleForTenant(featureId, ruleId, tenantId);
        rule.setAttribute(attribute);
        rule.setOperator(operator);
        rule.setValueJson(encodeValue(value));
        rule.setPriority(priority);
        return featureRuleRepository.save(rule);
    }

    @Transactional
    public void deleteRule(Long featureId, Long ruleId, Long tenantId) {
        FeatureRule rule = findRuleForTenant(featureId, ruleId, tenantId);
        featureRuleRepository.delete(rule);
    }

    public List<RuleResponse> listRules(Long featureId, Long envId, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);
        return featureRuleRepository.findAllByFeatureEnvStatusIdOrderByPriorityAsc(fes.getId())
                .stream()
                .map(this::toRuleResponse)
                .toList();
    }

    // ---------- Flag evaluation ----------

    public FlagEvaluationResponse evaluateFlag(Long featureId, Long envId, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);
        FeatureEnvStatus fes = featureEnvStatusRepository.findByFeatureIdAndEnvId(featureId, envId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_FEATURE_ENV_STATUS_NOT_FOUND)));

        List<String> targetUsers = featureUserOverrideRepository.findAllByFeatureEnvStatusId(fes.getId())
                .stream()
                .filter(FeatureUserOverride::isEnabled)
                .map(FeatureUserOverride::getExternalUserId)
                .toList();

        List<RuleResponse> rules = featureRuleRepository
                .findAllByFeatureEnvStatusIdOrderByPriorityAsc(fes.getId())
                .stream()
                .map(this::toRuleResponse)
                .toList();

        return new FlagEvaluationResponse(
                feature.getKey(),
                fes.getEnv().getName(),
                fes.isEnabled(),
                fes.getRolloutPercentage(),
                targetUsers,
                rules
        );
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

    private String encodeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(I18N.getMessage(Messages.FEATURE_RULE_VALUE_INVALID), e);
        }
    }

    private Object decodeValue(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(I18N.getMessage(Messages.FEATURE_RULE_VALUE_INVALID), e);
        }
    }

    private RuleResponse toRuleResponse(FeatureRule rule) {
        return new RuleResponse(rule.getAttribute(), rule.getOperator(), decodeValue(rule.getValueJson()));
    }
}