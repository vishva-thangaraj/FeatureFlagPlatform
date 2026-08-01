package com.feature_flag.platform.feature.mapper;

import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.feature.dto.RuleRequest;
import com.feature_flag.platform.feature.dto.RuleResponse;
import com.feature_flag.platform.feature.entity.FeatureRule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single source of truth for (de)serializing a rule's value array and mapping between the entity and
 * its DTOs. Shared by FeatureService (writes) and FlagConfigService (reads) so the two can never
 * diverge on the JSON-array contract (D5).
 */
@Component
public class FeatureRuleMapper {

    private final ObjectMapper objectMapper;

    public FeatureRuleMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encodeValues(List<Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(I18N.getMessage(Messages.FEATURE_RULE_VALUE_INVALID), e);
        }
    }

    public List<Object> decodeValues(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(I18N.getMessage(Messages.FEATURE_RULE_VALUE_INVALID), e);
        }
    }

    public RuleResponse toRuleResponse(FeatureRule rule) {
        return new RuleResponse(
                rule.getId().toString(),
                rule.getAttribute(),
                rule.getOperator(),
                rule.getValueType(),
                decodeValues(rule.getValueJson()),
                rule.isCaseSensitive(),
                rule.isNegated(),
                rule.isEnabled(),
                rule.getPriority());
    }

    public RuleRequest toRuleRequest(FeatureRule rule) {
        return new RuleRequest(
                rule.getAttribute(),
                rule.getOperator(),
                rule.getValueType(),
                decodeValues(rule.getValueJson()),
                rule.isCaseSensitive(),
                rule.isNegated(),
                rule.isEnabled(),
                rule.getPriority());
    }
}
