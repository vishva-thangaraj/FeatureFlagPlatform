package com.feature_flag.platform.common.exception;

public class TenantAccessDeniedException extends ForbiddenException {

    public TenantAccessDeniedException(String message) {
        super(message, "TENANT_ACCESS_DENIED");
    }
}