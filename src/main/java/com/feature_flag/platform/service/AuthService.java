package com.feature_flag.platform.service;

import com.feature_flag.platform.config.I18N;
import com.feature_flag.platform.config.JwtProperties;
import com.feature_flag.platform.config.Messages;
import com.feature_flag.platform.dto.auth.AuthResponse;
import com.feature_flag.platform.dto.auth.CreateTenantRequest;
import com.feature_flag.platform.dto.auth.LoginRequest;
import com.feature_flag.platform.dto.auth.RefreshRequest;
import com.feature_flag.platform.dto.auth.RegisterRequest;
import com.feature_flag.platform.dto.auth.SwitchTenantRequest;
import com.feature_flag.platform.entity.Membership;
import com.feature_flag.platform.entity.RefreshToken;
import com.feature_flag.platform.entity.Role;
import com.feature_flag.platform.entity.Tenant;
import com.feature_flag.platform.entity.User;
import com.feature_flag.platform.enums.MembershipStatus;
import com.feature_flag.platform.enums.RoleName;
import com.feature_flag.platform.enums.UserStatus;
import com.feature_flag.platform.exception.EmailAlreadyExistsException;
import com.feature_flag.platform.exception.InvalidCredentialsException;
import com.feature_flag.platform.exception.InvalidTokenException;
import com.feature_flag.platform.exception.ResourceNotFoundException;
import com.feature_flag.platform.exception.TenantAccessDeniedException;
import com.feature_flag.platform.exception.UserSuspendedException;
import com.feature_flag.platform.repository.MembershipRepository;
import com.feature_flag.platform.repository.RefreshTokenRepository;
import com.feature_flag.platform.repository.RoleRepository;
import com.feature_flag.platform.repository.TenantRepository;
import com.feature_flag.platform.repository.UserRepository;
import com.feature_flag.platform.security.JwtTokenProvider;
import com.feature_flag.platform.security.TokenHashUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository,
                       TenantRepository tenantRepository,
                       MembershipRepository membershipRepository,
                       RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(
                    I18N.getMessage(Messages.AUTH_EMAIL_ALREADY_EXISTS, request.email()));
        }

        User user = User.builder()
                .email(request.email())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .passwordHash(passwordEncoder.encode(request.password()))
                .status(UserStatus.ACTIVE)
                .build();
        user = userRepository.save(user);

        // No tenant context — user just signed up without an org
        return buildAuthResponse(user, null, Collections.emptyList());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException(
                        I18N.getMessage(Messages.AUTH_INVALID_CREDENTIALS)));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException(
                    I18N.getMessage(Messages.AUTH_INVALID_CREDENTIALS));
        }

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new UserSuspendedException(
                    I18N.getMessage(Messages.AUTH_USER_SUSPENDED));
        }

        List<Membership> activeMemberships = membershipRepository.findByUserId(user.getId()).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .toList();

        // User has no memberships — return to
        // ken without tenant context
        if (activeMemberships.isEmpty()) {
            return buildAuthResponse(user, null, Collections.emptyList());
        }

        Membership membership;
        if (request.tenantId() != null) {
            membership = activeMemberships.stream()
                    .filter(m -> m.getTenant().getId().equals(request.tenantId()))
                    .findFirst()
                    .orElseThrow(() -> new TenantAccessDeniedException(
                            I18N.getMessage(Messages.TENANT_NO_ACTIVE_MEMBERSHIP)));
        } else {
            // No tenant specified — pick the first active membership (could be improved with "last used tenant" tracking)
            membership = activeMemberships.get(0);
        }

        List<String> roles = membership.getRoles().stream()
                .map(Role::getName)
                .toList();

        return buildAuthResponse(user, membership.getTenant(), roles);
    }

    @Transactional
    public AuthResponse createTenant(CreateTenantRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_USER_NOT_FOUND)));

        Tenant tenant = Tenant.builder()
                .name(request.tenantName())
                .build();
        tenant = tenantRepository.save(tenant);

        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name())
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_ROLE_NOT_FOUND, RoleName.OWNER.name())));

        Membership membership = Membership.builder()
                .user(user)
                .tenant(tenant)
                .status(MembershipStatus.ACTIVE)
                .roles(Set.of(ownerRole))
                .build();
        membershipRepository.save(membership);

        List<String> roles = List.of(RoleName.OWNER.name());
        return buildAuthResponse(user, tenant, roles);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String tokenHash = TokenHashUtil.sha256(request.refreshToken());
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException(
                        I18N.getMessage(Messages.TOKEN_INVALID)));

        if (storedToken.isRevoked()) {
            // Replay detection: revoke the entire chain for this user+tenant
            if (storedToken.getTenant() != null) {
                refreshTokenRepository.revokeAllByUserIdAndTenantId(
                        storedToken.getUser().getId(), storedToken.getTenant().getId());
            }
            throw new InvalidTokenException(
                    I18N.getMessage(Messages.TOKEN_REVOKED));
        }

        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            storedToken.setRevoked(true);
            refreshTokenRepository.save(storedToken);
            throw new InvalidTokenException(
                    I18N.getMessage(Messages.TOKEN_EXPIRED));
        }

        User user = storedToken.getUser();
        Tenant tenant = storedToken.getTenant();

        List<String> roles;
        if (tenant != null) {
            // Fetch fresh roles from current membership
            Membership membership = membershipRepository.findByUserIdAndTenantId(user.getId(), tenant.getId())
                    .orElseThrow(() -> new TenantAccessDeniedException(
                            I18N.getMessage(Messages.TENANT_MEMBERSHIP_NO_LONGER_EXISTS)));

            if (membership.getStatus() != MembershipStatus.ACTIVE) {
                throw new TenantAccessDeniedException(
                        I18N.getMessage(Messages.TENANT_MEMBERSHIP_NO_LONGER_ACTIVE));
            }

            roles = membership.getRoles().stream()
                    .map(Role::getName)
                    .toList();
        } else {
            roles = Collections.emptyList();
        }

        // Rotate: revoke old, create new
        String newRawRefreshToken = jwtTokenProvider.generateRefreshToken();
        String newTokenHash = TokenHashUtil.sha256(newRawRefreshToken);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .tenant(tenant)
                .tokenHash(newTokenHash)
                .expiresAt(Instant.now().plusMillis(jwtProperties.getRefreshTokenExpirationMs()))
                .build();
        newRefreshToken = refreshTokenRepository.save(newRefreshToken);

        storedToken.setRevoked(true);
        storedToken.setReplacedBy(newRefreshToken);
        refreshTokenRepository.save(storedToken);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(),
                tenant != null ? tenant.getId() : null, roles);

        return new AuthResponse(
                accessToken,
                newRawRefreshToken,
                user.getId(),
                user.getEmail(),
                tenant != null ? tenant.getId() : null,
                roles,
                jwtProperties.getAccessTokenExpirationMs()
        );
    }

    @Transactional
    public void logout(RefreshRequest request) {
        String tokenHash = TokenHashUtil.sha256(request.refreshToken());
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    @Transactional
    public AuthResponse switchTenant(SwitchTenantRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_USER_NOT_FOUND)));

        Membership membership = membershipRepository.findByUserIdAndTenantId(userId, request.tenantId())
                .orElseThrow(() -> new TenantAccessDeniedException(
                        I18N.getMessage(Messages.TENANT_NO_MEMBERSHIP)));

        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new TenantAccessDeniedException(
                    I18N.getMessage(Messages.TENANT_MEMBERSHIP_NOT_ACTIVE));
        }

        List<String> roles = membership.getRoles().stream()
                .map(Role::getName)
                .toList();

        return buildAuthResponse(user, membership.getTenant(), roles);
    }

    private AuthResponse buildAuthResponse(User user, Tenant tenant, List<String> roles) {
        Long tenantId = tenant != null ? tenant.getId() : null;
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), tenantId, roles);
        String rawRefreshToken = jwtTokenProvider.generateRefreshToken();
        String tokenHash = TokenHashUtil.sha256(rawRefreshToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tenant(tenant)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusMillis(jwtProperties.getRefreshTokenExpirationMs()))
                .build();
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                user.getId(),
                user.getEmail(),
                tenantId,
                roles,
                jwtProperties.getAccessTokenExpirationMs()
        );
    }
}
