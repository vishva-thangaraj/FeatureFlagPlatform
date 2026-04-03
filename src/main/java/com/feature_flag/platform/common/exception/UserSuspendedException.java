package com.feature_flag.platform.common.exception;

public class UserSuspendedException extends ForbiddenException {

    public UserSuspendedException(String message) {
        super(message, "USER_SUSPENDED");
    }
}