package com.feature_flag.platform.auth.controller;

import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.LoginRequest;
import com.feature_flag.platform.auth.dto.MeResponse;
import com.feature_flag.platform.auth.dto.MessageResponse;
import com.feature_flag.platform.auth.dto.RefreshRequest;
import com.feature_flag.platform.auth.dto.RegisterRequest;
import com.feature_flag.platform.auth.dto.SwitchTenantRequest;
import com.feature_flag.platform.auth.service.AuthService;
import com.feature_flag.platform.auth.service.TokenService;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.security.TenantUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;

    public AuthController(AuthService authService, TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthResponse response = tokenService.refresh(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(@Valid @RequestBody RefreshRequest request) {
        tokenService.logout(request);
        return ResponseEntity.ok(new MessageResponse(I18N.getMessage(Messages.AUTH_LOGOUT_SUCCESS)));
    }

    /**
     * The caller's identity, for the account menu. Not tenant-scoped -- see TenantContextFilter -- so it
     * answers for a user who belongs to no tenant yet, which is exactly the user the shell has to render
     * something for right after registration.
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal TenantUserDetails userDetails) {
        return ResponseEntity.ok(authService.me(userDetails.getUserId(), userDetails.getTenantId()));
    }

    @PostMapping("/switch-tenant")
    public ResponseEntity<AuthResponse> switchTenant(@Valid @RequestBody SwitchTenantRequest request,
                                                     @AuthenticationPrincipal TenantUserDetails userDetails) {
        AuthResponse response = authService.switchTenant(request, userDetails.getUserId());
        return ResponseEntity.ok(response);
    }
}