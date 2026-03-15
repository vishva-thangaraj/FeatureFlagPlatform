package com.feature_flag.platform.config;

public final class Messages {

    private Messages() {
    }

    // Auth
    public static final String AUTH_EMAIL_ALREADY_EXISTS = "auth.email.already.exists";
    public static final String AUTH_INVALID_CREDENTIALS = "auth.invalid.credentials";
    public static final String AUTH_USER_SUSPENDED = "auth.user.suspended";
    public static final String AUTH_LOGOUT_SUCCESS = "auth.logout.success";

    // Token
    public static final String TOKEN_INVALID = "token.invalid";
    public static final String TOKEN_REVOKED = "token.revoked";
    public static final String TOKEN_EXPIRED = "token.expired";

    // Tenant
    public static final String TENANT_NO_ACTIVE_MEMBERSHIP = "tenant.no.active.membership";
    public static final String TENANT_NO_MEMBERSHIP = "tenant.no.membership";
    public static final String TENANT_MEMBERSHIP_NOT_ACTIVE = "tenant.membership.not.active";
    public static final String TENANT_MEMBERSHIP_NO_LONGER_EXISTS = "tenant.membership.no.longer.exists";
    public static final String TENANT_MEMBERSHIP_NO_LONGER_ACTIVE = "tenant.membership.no.longer.active";

    // Resource
    public static final String RESOURCE_USER_NOT_FOUND = "resource.user.not.found";
    public static final String RESOURCE_ROLE_NOT_FOUND = "resource.role.not.found";
}
