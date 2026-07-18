package com.feature_flag.platform.feature.mapper;

import com.feature_flag.platform.feature.dto.FeatureEnvStatusResponse;
import com.feature_flag.platform.feature.dto.FeatureUserOverrideResponse;
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.entity.FeatureUserOverride;
import org.springframework.stereotype.Component;

@Component
public class FeatureEnvStatusMapper {

    public FeatureEnvStatusResponse toFeatureEnvStatusResponse(FeatureEnvStatus status) {
        return new FeatureEnvStatusResponse(
                status.getId().toString(),
                status.getFeature().getId().toString(),
                status.getEnv().getId().toString(),
                status.getEnv().getName(),
                status.isEnabled(),
                status.getRolloutPercentage()
        );
    }

    public FeatureUserOverrideResponse toFeatureUserOverrideResponse(FeatureUserOverride override) {
        return new FeatureUserOverrideResponse(
                override.getId().toString(),
                override.getExternalUserId(),
                override.isEnabled()
        );
    }
}