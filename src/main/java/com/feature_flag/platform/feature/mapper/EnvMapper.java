package com.feature_flag.platform.feature.mapper;

import com.feature_flag.platform.feature.dto.EnvResponse;
import com.feature_flag.platform.feature.entity.Env;
import org.springframework.stereotype.Component;

@Component
public class EnvMapper {

    public static EnvResponse toEnvResponse(Env env) {
        return new EnvResponse(
                env.getId().toString(),
                env.getName(),
                env.getDescription(),
                env.getCreatedAt()
        );
    }
}