package com.feature_flag.platform.feature.mapper;

import com.feature_flag.platform.feature.dto.FeatureResponse;
import com.feature_flag.platform.feature.entity.Feature;
import org.springframework.stereotype.Component;

@Component
public class FeatureMapper {

    public static FeatureResponse toFeatureResponse(Feature feature) {
        return new FeatureResponse(
                feature.getId().toString(),
                feature.getKey(),
                feature.getDescription(),
                feature.getCreatedAt()
        );
    }
}