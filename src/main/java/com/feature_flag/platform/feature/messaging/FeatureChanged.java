package com.feature_flag.platform.feature.messaging;

import com.feature_flag.platform.feature.events.FeatureChangeEvent;

public record FeatureChanged(FeatureChangeEvent event) {
}
