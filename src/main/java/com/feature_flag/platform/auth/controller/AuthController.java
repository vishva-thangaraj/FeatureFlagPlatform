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
import com.feature_flag.platform.common.exception.InvalidTokenException;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.security.RefreshTokenCookies;
import com.feature_flag.platform.common.security.TenantUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final RefreshTokenCookies refreshTokenCookies;

    public AuthController(AuthService authService, TokenService tokenService,
                          RefreshTokenCookies refreshTokenCookies) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.refreshTokenCookies = refreshTokenCookies;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(refreshTokenCookies.apply(response, httpRequest, httpResponse));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(refreshTokenCookies.apply(response, httpRequest, httpResponse));
    }

    /**
     * The body is optional: a browser on cookie transport sends the token in the cookie and no body at
     * all. One of the two must carry it.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody(required = false) RefreshRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        AuthResponse response = tokenService.refresh(resolve(request, httpRequest));
        return ResponseEntity.ok(refreshTokenCookies.apply(response, httpRequest, httpResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(@Valid @RequestBody(required = false) RefreshRequest request,
                                                  HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        tokenService.logout(resolve(request, httpRequest));
        // Cleared whether or not this caller used one. Logging out is the one moment where leaving a
        // seven-day credential in the browser would be indefensible, and clearing a cookie that was never
        // set costs a header.
        refreshTokenCookies.clear(httpResponse);
        return ResponseEntity.ok(new MessageResponse(I18N.getMessage(Messages.AUTH_LOGOUT_SUCCESS)));
    }

    /**
     * The refresh token for this request, from the body or, failing that, the cookie.
     *
     * <p>Absent from both is a malformed request, not an invalid token, but it is reported the same way
     * the service reports a token it cannot use: the distinction is of no use to the caller and spelling
     * it out would tell an attacker probing the endpoint which of the two they got wrong.
     */
    private RefreshRequest resolve(RefreshRequest body, HttpServletRequest httpRequest) {
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            return body;
        }
        return refreshTokenCookies.read(httpRequest)
                .map(RefreshRequest::new)
                .orElseThrow(() -> new InvalidTokenException(
                        I18N.getMessage(Messages.TOKEN_INVALID)));
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
                                                     @AuthenticationPrincipal TenantUserDetails userDetails,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse httpResponse) {
        AuthResponse response = authService.switchTenant(request, userDetails.getUserId());
        return ResponseEntity.ok(refreshTokenCookies.apply(response, httpRequest, httpResponse));
    }
}