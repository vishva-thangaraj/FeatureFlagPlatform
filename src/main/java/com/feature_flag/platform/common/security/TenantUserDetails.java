package com.feature_flag.platform.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
public class TenantUserDetails implements UserDetails {

    private final Long userId;
    private final String email;
    private final Long tenantId;
    private final List<String> roles;
    private final Collection<? extends GrantedAuthority> authorities;

    public TenantUserDetails(Long userId, String email, Long tenantId, List<String> roles) {
        this.userId = userId;
        this.email = email;
        this.tenantId = tenantId;
        this.roles = roles;
        this.authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }
}