package com.feature_flag.platform.common.exception;

public class InvalidCredentialsException extends UnauthorizedException {

    public InvalidCredentialsException(String message) {
        super(message, "INVALID_CREDENTIALS");
    }
}