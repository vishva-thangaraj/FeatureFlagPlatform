package com.feature_flag.platform.auth.mapper;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.tenant.entity.Tenant;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AuthMapper {

    public AuthResponse toAuthResponse(User user, Tenant tenant, List<String> roles,
                                       String accessToken, String refreshToken, long expiresIn) {
        return new AuthResponse(
                accessToken,
                refreshToken,
                user.getId(),
                user.getEmail(),
                tenant != null ? tenant.getId() : null,
                roles,
                expiresIn
        );
    }
}