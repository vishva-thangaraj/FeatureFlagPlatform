package com.feature_flag.platform.auth.service;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.RefreshRequest;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.tenant.entity.Tenant;

import java.util.List;

public interface TokenService {

    AuthResponse refresh(RefreshRequest request);

    void logout(RefreshRequest request);

    AuthResponse createTokenPair(User user, Tenant tenant, List<String> roles);
}