package com.feature_flag.platform.common.config;

public final class Messages {

    private Messages() {
    }

    // Auth
    public static final String AUTH_EMAIL_ALREADY_EXISTS = "auth.email.already.exists";
    public static final String AUTH_INVALID_CREDENTIALS = "auth.invalid.credentials";
    public static final String AUTH_USER_SUSPENDED = "auth.user.suspended";
    public static final String AUTH_LOGOUT_SUCCESS = "auth.logout.success";
    public static final String UNAUTHORIZED = "auth.user.unauthorized";

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
    public static final String TENANT_HEADER_INVALID = "tenant.header.invalid";
    public static final String TENANT_PATH_INVALID = "tenant.path.invalid";
    public static final String TENANT_ROLE_REQUIRED = "tenant.role.required";
    public static final String RESOURCE_TENANT_NOT_FOUND = "resource.tenant.not.found";

    // User Invitation
    public static final String USER_INVITATION_ALREADY_EXISTS = "user.invitation.already.exists";
    public static final String INVITATION_TOKEN_INVALID = "invitation.token.invalid";
    public static final String INVITATION_ALREADY_PROCESSED = "invitation.already.processed";
    public static final String INVITATION_EXPIRED = "invitation.expired";
    public static final String INVITATION_ACCEPTED = "invitation.accepted";
    public static final String INVITATION_AUTHENTICATION_REQUIRED = "invitation.authentication.required";
    public static final String INVITATION_NOT_FOR_CALLER = "invitation.not.for.caller";
    public static final String INVITATION_REGISTRATION_REQUIRED = "invitation.registration.required";
    public static final String INVITATION_REGISTRATION_EMAIL_MISMATCH = "invitation.registration.email.mismatch";

    // Resource
    public static final String RESOURCE_USER_NOT_FOUND = "resource.user.not.found";
    public static final String RESOURCE_ROLE_NOT_FOUND = "resource.role.not.found";
    public static final String RESOURCE_MEMBERSHIP_NOT_FOUND = "resource.membership.not.found";
    public static final String TENANT_CANNOT_REMOVE_OWNER = "tenant.cannot.remove.owner";
    public static final String TENANT_CANNOT_CHANGE_OWNER_ROLE = "tenant.cannot.change.owner.role";


    //Feature
    public static final String FEATURE_KEY_ALREADY_EXISTS = "feature.key.already.exists";

    public static final String RESOURCE_FEATURE_NOT_FOUND = "resource.feature.not.found";

    //ENV
    public static final String ENV_NAME_ALREADY_EXISTS = "env.name.already.exists";
    public static final String RESOURCE_ENV_NOT_FOUND = "resource.env.not.found";

    // Feature rule
    public static final String RESOURCE_FEATURE_RULE_NOT_FOUND = "resource.feature.rule.not.found";
    public static final String RESOURCE_FEATURE_ENV_STATUS_NOT_FOUND = "resource.feature.env.status.not.found";
    public static final String FEATURE_RULE_VALUE_INVALID = "feature.rule.value.invalid";
    public static final String FEATURE_RULE_OPERATOR_VALUE_MISMATCH = "feature.rule.operator.value.mismatch";
    public static final String FEATURE_RULE_DUPLICATE_ATTRIBUTE = "feature.rule.duplicate.attribute";
    public static final String FEATURE_RULE_VALUE_TYPE_MISMATCH = "feature.rule.value.type.mismatch";
    public static final String RESOURCE_FEATURE_OVERRIDE_NOT_FOUND = "resource.feature.override.not.found";
}