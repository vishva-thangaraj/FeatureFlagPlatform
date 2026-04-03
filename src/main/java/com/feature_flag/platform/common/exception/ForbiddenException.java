package com.feature_flag.platform.common.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends BaseException {

    public ForbiddenException(String message) {
        super(message, "FORBIDDEN", HttpStatus.FORBIDDEN);
    }

    public ForbiddenException(String message, String errorCode) {
        super(message, errorCode, HttpStatus.FORBIDDEN);
    }
}