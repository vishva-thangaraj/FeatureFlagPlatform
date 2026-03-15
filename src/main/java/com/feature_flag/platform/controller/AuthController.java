package com.feature_flag.platform.controller;

import com.feature_flag.platform.dto.auth.AuthResponse;
import com.feature_flag.platform.dto.auth.CreateTenantRequest;
import com.feature_flag.platform.dto.auth.LoginRequest;
import com.feature_flag.platform.dto.auth.MessageResponse;
import com.feature_flag.platform.dto.auth.RefreshRequest;
import com.feature_flag.platform.dto.auth.RegisterRequest;
import com.feature_flag.platform.dto.auth.SwitchTenantRequest;
import com.feature_flag.platform.config.I18N;
import com.feature_flag.platform.config.Messages;
import com.feature_flag.platform.security.TenantUserDetails;
import com.feature_flag.platform.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
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
        AuthResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(new MessageResponse(I18N.getMessage(Messages.AUTH_LOGOUT_SUCCESS)));
    }

    @PostMapping("/create-tenant")
    public ResponseEntity<AuthResponse> createTenant(@Valid @RequestBody CreateTenantRequest request,
                                                     @AuthenticationPrincipal TenantUserDetails userDetails) {
        AuthResponse response = authService.createTenant(request, userDetails.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/switch-tenant")
    public ResponseEntity<AuthResponse> switchTenant(@Valid @RequestBody SwitchTenantRequest request,
                                                     @AuthenticationPrincipal TenantUserDetails userDetails) {
        AuthResponse response = authService.switchTenant(request, userDetails.getUserId());
        return ResponseEntity.ok(response);
    }
}
