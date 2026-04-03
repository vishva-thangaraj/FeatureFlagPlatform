package com.feature_flag.platform.common.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends BaseException {

    public UnauthorizedException(String message) {
        super(message, "UNAUTHORIZED", HttpStatus.UNAUTHORIZED);
    }

    public UnauthorizedException(String message, String errorCode) {
        super(message, errorCode, HttpStatus.UNAUTHORIZED);
    }
}