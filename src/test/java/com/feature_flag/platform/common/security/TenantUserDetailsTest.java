package com.feature_flag.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantUserDetailsTest {

    @Test
    void constructsWithAllFields() {
        TenantUserDetails details = new TenantUserDetails(1L, "user@test.com", 100L, List.of("OWNER", "ADMIN"));
        assertEquals(1L, details.getUserId());
        assertEquals("user@test.com", details.getEmail());
        assertEquals(100L, details.getTenantId());
        assertEquals(List.of("OWNER", "ADMIN"), details.getRoles());
    }

    @Test
    void authoritiesPrefixedWithROLE() {
        TenantUserDetails details = new TenantUserDetails(1L, "u@t.com", 2L, List.of("OWNER", "VIEWER"));
        List<String> authorityStrings = details.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
        assertEquals(List.of("ROLE_OWNER", "ROLE_VIEWER"), authorityStrings);
    }

    @Test
    void emptyRolesProducesEmptyAuthorities() {
        TenantUserDetails details = new TenantUserDetails(1L, "u@t.com", null, Collections.emptyList());
        assertTrue(details.getAuthorities().isEmpty());
        assertTrue(details.getRoles().isEmpty());
    }

    @Test
    void tenantIdCanBeNull() {
        TenantUserDetails details = new TenantUserDetails(1L, "u@t.com", null, List.of("MEMBER"));
        assertNull(details.getTenantId());
    }

    @Test
    void usernameReturnsEmail() {
        TenantUserDetails details = new TenantUserDetails(1L, "user@test.com", 2L, List.of());
        assertEquals("user@test.com", details.getUsername());
    }

    @Test
    void passwordReturnsNull() {
        TenantUserDetails details = new TenantUserDetails(1L, "u@t.com", 2L, List.of());
        assertNull(details.getPassword());
    }
}
