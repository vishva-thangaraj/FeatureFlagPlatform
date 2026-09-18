package com.feature_flag.platform.feature.service;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ConflictException;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.feature.events.ChangeType;
import com.feature_flag.platform.feature.events.FeatureChangeEvent;
import com.feature_flag.platform.feature.dto.*;
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.entity.Feature;
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.entity.FeatureRule;
import com.feature_flag.platform.feature.entity.FeatureUserOverride;
import com.feature_flag.platform.feature.mapper.FeatureEnvStatusMapper;
import com.feature_flag.platform.feature.mapper.FeatureMapper;
import com.feature_flag.platform.feature.mapper.FeatureRuleMapper;
import com.feature_flag.platform.feature.messaging.FeatureChanged;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.feature.repository.FeatureRuleRepository;
import com.feature_flag.platform.feature.repository.FeatureUserOverrideRepository;
import com.feature_flag.platform.feature.validation.RuleValidator;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FeatureService {

    /**
     * Rollout a fresh status row starts at. {@code 0} means "off for everyone" in the SDK evaluation
     * contract, which is not what "enable this flag" can be allowed to mean.
     */
    static final int FULL_ROLLOUT = 100;

    private final FeatureRepository featureRepository;
    private final TenantRepository tenantRepository;
    private final EnvRepository envRepository;
    private final FeatureEnvStatusRepository featureEnvStatusRepository;
    private final FeatureRuleRepository featureRuleRepository;
    private final FeatureUserOverrideRepository featureUserOverrideRepository;
    private final FeatureEnvStatusMapper featureEnvStatusMapper;
    private final FeatureRuleMapper featureRuleMapper;
    private final RuleValidator ruleValidator;
    private final AuditorAware<Long> auditAware;
    private final ApplicationEventPublisher eventPublisher;

    public FeatureService(FeatureRepository featureRepository,
                          TenantRepository tenantRepository,
                          EnvRepository envRepository,
                          FeatureEnvStatusRepository featureEnvStatusRepository,
                          FeatureRuleRepository featureRuleRepository,
                          FeatureUserOverrideRepository featureUserOverrideRepository,
                          FeatureEnvStatusMapper featureEnvStatusMapper,
                          FeatureRuleMapper featureRuleMapper,
                          RuleValidator ruleValidator, AuditorAware<Long> auditAware, ApplicationEventPublisher eventPublisher) {
        this.featureRepository = featureRepository;
        this.tenantRepository = tenantRepository;
        this.envRepository = envRepository;
        this.featureEnvStatusRepository = featureEnvStatusRepository;
        this.featureRuleRepository = featureRuleRepository;
        this.featureUserOverrideRepository = featureUserOverrideRepository;
        this.featureEnvStatusMapper = featureEnvStatusMapper;
        this.featureRuleMapper = featureRuleMapper;
        this.ruleValidator = ruleValidator;
        this.auditAware = auditAware;
        this.eventPublisher = eventPublisher;
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
        // createdAt is not set here on purpose: BaseEntity.createdAt is @CreationTimestamp. The line
        // that used to stand here read LocalDateTime.now().toInstant(ZoneOffset.UTC), which relabels
        // the server's local wall-clock as UTC — five and a half hours adrift on an IST host.

        featureRepository.save(feature);
        return FeatureMapper.toFeatureResponse(feature);
    }

    @Transactional(readOnly = true)
    public FeatureListResponse getFeaturesList(Long tenantId) {
        List<Feature> features = featureRepository.findAllByTenantId(tenantId);
        List<FeatureResponse> featureResponses = features.stream()
                .map(FeatureMapper::toFeatureResponse)
                .toList();
        return new FeatureListResponse(featureResponses);
    }

    @Transactional(readOnly = true)
    public FeatureResponse getFeature(Long featureId, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);
        return FeatureMapper.toFeatureResponse(feature);
    }

    /**
     * Partial update: an absent (null) field is left alone, matching {@code ENVService.updateEnv}.
     * This used to assign {@code request.description()} unconditionally, so {@code PUT {}} — the
     * shape a client sends when it means "change nothing else" — erased the description. To clear it
     * deliberately, send {@code {"description": ""}}.
     */
    @Transactional
    public FeatureResponse updateFeature(Long featureId, UpdateFeatureRequest request, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);
        if (request.description() != null) {
            feature.setDescription(request.description());
        }
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
        // Whole rows are about to disappear, and the bulk config payload's lastModified is a maximum
        // over the rows that remain — deleting anything but the newest flag would leave that maximum
        // untouched, so every polling SDK would keep getting 304 and keep serving the deleted flag.
        // The environment carries the timestamp that no row deletion can hide.
        touchEnvironments(statuses);
        featureEnvStatusRepository.deleteAll(statuses);
        featureRepository.delete(feature);
    }

    private Feature findByIdAndTenant(Long featureId, Long tenantId) {
        return featureRepository.findById(featureId)
                .filter(f -> f.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_FEATURE_NOT_FOUND)));
    }

    /**
     * Enable or disable a flag in one environment.
     *
     * <p>{@code rolloutPercentage} is optional. Omitting it on an <em>existing</em> row leaves the
     * stored percentage alone — a kill-switch toggle must not silently discard a deliberate 25%
     * rollout. Omitting it on the <em>first</em> enable used to leave the column at its 0 default,
     * and the SDK contract reads {@code rolloutPercentage <= 0} as off-for-everyone: the dashboard
     * showed the flag on while every SDK returned false, with nothing anywhere saying why. A brand
     * new row therefore defaults to fully rolled out; a percentage is something you opt into.
     */
    @Transactional
    public void setFeatureStatus(Long featureId, @Valid FeatureEnableRequest request, Long tenantId) {
        Feature feature = findByIdAndTenant(featureId, tenantId);

        Long envID = request.envId();

        Env env = envRepository.findById(envID)
                .filter(e -> e.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException(I18N.getMessage(Messages.RESOURCE_ENV_NOT_FOUND)));

        FeatureEnvStatus featureEnvStatus = featureEnvStatusRepository.findByFeatureIdAndEnvId(featureId, envID)
                .orElseGet(() -> FeatureEnvStatus.builder()
                        .feature(feature)
                        .env(env)
                        .rolloutPercentage(FULL_ROLLOUT)
                        .build());
        featureEnvStatus.setEnabled(request.enabled());
        if (request.rolloutPercentage() != null) {
            featureEnvStatus.setRolloutPercentage(request.rolloutPercentage());
        }
        featureEnvStatusRepository.save(featureEnvStatus);

        FeatureChangeEvent event = FeatureChangeEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setChangeType(ChangeType.STATUS_CHANGED)
                .setEnvName(env.getName())
                .setFeatureKey(feature.getKey())
                .setTenantId(String.valueOf(tenantId))
                .setActorUserId(auditAware.getCurrentAuditor().map(Object::toString).orElse(null))
                .setOccurredAt(Instant.now())
                .setDetails(Map.of(
                        "enabled", String.valueOf(featureEnvStatus.isEnabled()),
                        "rolloutPercentage", String.valueOf(featureEnvStatus.getRolloutPercentage())))
                .build();
        eventPublisher.publishEvent(new FeatureChanged(event));
    }

    // ---------- Feature env status listing ----------

    /**
     * Read methods here are transactional for a reason: their mappers walk LAZY associations
     * ({@code status.getEnv().getName()}), and without a transaction the only thing keeping that from
     * throwing {@code LazyInitializationException} is {@code spring.jpa.open-in-view}, which is unset
     * and therefore on by default. These would have started failing the day anyone turned it off.
     */
    @Transactional(readOnly = true)
    public List<FeatureEnvStatusResponse> listEnvStatuses(Long featureId, Long tenantId) {
        findByIdAndTenant(featureId, tenantId);
        return featureEnvStatusRepository.findAllByFeatureId(featureId)
                .stream()
                .map(featureEnvStatusMapper::toFeatureEnvStatusResponse)
                .toList();
    }

    // ---------- Feature rules ----------

    @Transactional(readOnly = true)
    public List<RuleResponse> listRules(Long featureId, Long envId, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);
        return featureRuleRepository.findAllByFeatureEnvStatusIdOrderByPriorityAsc(fes.getId())
                .stream()
                .map(featureRuleMapper::toRuleResponse)
                .toList();
    }

    /**
     * Attribute names already in use, for the rule editor's autocomplete. The result is deliberately
     * tenant-wide rather than per-feature — suggestions are more useful that way — but the feature id
     * in the path is still checked, so a typo or another tenant's id answers 404 instead of 200 with
     * a list that has nothing to do with it.
     */
    @Transactional(readOnly = true)
    public List<String> listAttributes(Long featureId, Long tenantId) {
        findByIdAndTenant(featureId, tenantId);
        return featureRuleRepository.findDistinctAttributesByTenantId(tenantId);
    }

    @Transactional
    public RuleResponse addRule(Long featureId, Long envId, RuleRequest request, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);

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
        rule = featureRuleRepository.save(rule);
        touch(fes);

        FeatureChangeEvent event = FeatureChangeEvent.newBuilder()
                                .setEventId(UUID.randomUUID().toString())
                                .setChangeType(ChangeType.RULE_ADDED)
                                .setEnvName(fes.getEnv().getName())
                                .setFeatureKey(fes.getFeature().getKey())
                                .setTenantId(String.valueOf(tenantId))
                                .setActorUserId(auditAware.getCurrentAuditor().map(Object::toString).orElse(null))
                                .setOccurredAt(Instant.now())
                                // Identifier only — see the privacy note in docs/feature-events.md:
                                // attribute names and valueJson are customer-supplied targeting data
                                // (often end-user emails) and must not reach this shared, retained topic.
                                .setDetails(Map.of("ruleId", String.valueOf(rule.getId())))
                                .build();
        eventPublisher.publishEvent(new FeatureChanged(event));
        return featureRuleMapper.toRuleResponse(rule);
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
        FeatureRule saved = featureRuleRepository.save(rule);
        touch(saved.getFeatureEnvStatus());

        // Identifier only — no customer-supplied targeting data on the shared topic.
        publishChange(ChangeType.RULE_UPDATED, saved.getFeatureEnvStatus(), tenantId, Map.of(
                "ruleId", String.valueOf(saved.getId())));
        return featureRuleMapper.toRuleResponse(saved);
    }

    @Transactional
    public void deleteRule(Long featureId, Long ruleId, Long tenantId) {
        FeatureRule rule = findRuleForTenant(featureId, ruleId, tenantId);

        // Identifier only — no customer-supplied targeting data on the shared topic.
        publishChange(ChangeType.RULE_DELETED, rule.getFeatureEnvStatus(), tenantId, Map.of(
                "ruleId", String.valueOf(rule.getId())));
        featureRuleRepository.delete(rule);
        touch(rule.getFeatureEnvStatus());
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
        touch(fes);

        // One coarse event for the whole set swap — the WebSocket consumer re-fetches config anyway.
        publishChange(ChangeType.RULES_REPLACED, fes, tenantId, Map.of(
                "ruleCount", String.valueOf(saved.size())));
        return saved;
    }

    // ---------- User overrides ----------

    @Transactional(readOnly = true)
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
        FeatureUserOverride persisted = featureUserOverrideRepository.save(override);
        touch(fes);

        publishChange(ChangeType.OVERRIDE_SET, fes, tenantId, Map.of(
                "externalUserId", persisted.getExternalUserId(),
                "enabled", String.valueOf(persisted.isEnabled())));
        return featureEnvStatusMapper.toFeatureUserOverrideResponse(persisted);
    }

    @Transactional
    public void deleteOverride(Long featureId, Long envId, String externalUserId, Long tenantId) {
        FeatureEnvStatus fes = findFeatureEnvStatus(featureId, envId, tenantId);
        FeatureUserOverride override = featureUserOverrideRepository
                .findByFeatureEnvStatusIdAndExternalUserId(fes.getId(), externalUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_FEATURE_OVERRIDE_NOT_FOUND)));

        // Build the event BEFORE the delete, while the override's fields are still readable.
        publishChange(ChangeType.OVERRIDE_DELETED, fes, tenantId, Map.of(
                "externalUserId", override.getExternalUserId()));
        featureUserOverrideRepository.delete(override);
        touch(fes);
    }

    // ---------- Helpers ----------

    /**
     * Stamp the status row so a change to one of its children is visible to a conditional request.
     *
     * <p>A flag's {@code lastModified} is the maximum {@code updatedAt} over the status row and the
     * rule/override rows that still exist, and the ETag is built from it. Adding or editing a child
     * raises that maximum by itself; <em>deleting</em> one cannot. Given rules at T5 and T9 the ETag
     * quotes T9, and deleting the T5 rule leaves the maximum at T9 — same ETag, so the next poll with
     * {@code If-None-Match} gets 304 and the SDK goes on enforcing a rule that no longer exists, for
     * as long as nothing else in that flag changes. Stamping the parent gives the deletion somewhere
     * to land.
     *
     * <p>Assigning {@code updatedAt} makes the entity dirty; Hibernate's {@code @UpdateTimestamp} then
     * writes the flush-time value over it. The assignment is what schedules the UPDATE.
     */
    private void touch(FeatureEnvStatus fes) {
        fes.setUpdatedAt(Instant.now());
        featureEnvStatusRepository.save(fes);
    }

    /** Same idea one level up: the env timestamp survives the deletion of every row beneath it. */
    private void touchEnvironments(List<FeatureEnvStatus> statuses) {
        Set<Long> seen = new HashSet<>();
        for (FeatureEnvStatus status : statuses) {
            Env env = status.getEnv();
            if (env == null || !seen.add(env.getId())) {
                continue;
            }
            env.setUpdatedAt(Instant.now());
            envRepository.save(env);
        }
    }

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

    private void publishChange(ChangeType changeType, FeatureEnvStatus fes, Long tenantId,
                               Map<String, String> details) {
        FeatureChangeEvent event = FeatureChangeEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setChangeType(changeType)
                .setTenantId(String.valueOf(tenantId))
                .setFeatureKey(fes.getFeature().getKey())
                .setEnvName(fes.getEnv().getName())
                .setActorUserId(auditAware.getCurrentAuditor().map(Object::toString).orElse(null))
                .setOccurredAt(Instant.now())
                .setDetails(details)
                .build();
        eventPublisher.publishEvent(new FeatureChanged(event));
    }
}