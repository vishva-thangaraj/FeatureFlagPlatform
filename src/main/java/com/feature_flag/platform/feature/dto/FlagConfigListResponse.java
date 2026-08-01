package com.feature_flag.platform.feature.dto;

import java.time.Instant;
import java.util.List;

public record FlagConfigListResponse(
        String environment,
        List<FlagConfigResponse> flags,
        Instant lastModified
) {
}
