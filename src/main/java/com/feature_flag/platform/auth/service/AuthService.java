package com.feature_flag.platform.auth.service;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.MeResponse;
import com.feature_flag.platform.auth.dto.LoginRequest;
import com.feature_flag.platform.auth.dto.RegisterRequest;
import com.feature_flag.platform.auth.dto.SwitchTenantRequest;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse switchTenant(SwitchTenantRequest request, Long userId);

    /**
     * The caller's own identity, plus the roles they actually hold in their default tenant.
     *
     * @param tenantId the tenant from the caller's token, or {@code null} if it carried none
     */
    MeResponse me(Long userId, Long tenantId);
}