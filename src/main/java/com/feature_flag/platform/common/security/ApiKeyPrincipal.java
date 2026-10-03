package com.feature_flag.platform.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * A machine caller authenticated by an API key. Deliberately not a {@link TenantUserDetails} with a null
 * user: every filter that type-checks the principal would then take its user branch on a null user.
 *
 * <p>Its single authority matches no role in {@code api-security.json}, so a key can never satisfy a
 * role-gated endpoint however the chain is later rearranged.
 */
@Getter
public class ApiKeyPrincipal implements UserDetails, TenantScopedPrincipal {

    public static final String AUTHORITY = "ROLE_SDK";

    private final Long apiKeyId;
    private final Long tenantId;
    private final Long envId;
    private final String envName;
    private final String prefix;

    public ApiKeyPrincipal(Long apiKeyId, Long tenantId, Long envId, String envName, String prefix) {
        this.apiKeyId = apiKeyId;
        this.tenantId = tenantId;
        this.envId = envId;
        this.envName = envName;
        this.prefix = prefix;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(AUTHORITY));
    }

    @Override
    public String getPassword() {
        return null;
    }

    /** The non-secret prefix: enough to identify the key in a log line, useless as a credential. */
    @Override
    public String getUsername() {
        return prefix;
    }
}
