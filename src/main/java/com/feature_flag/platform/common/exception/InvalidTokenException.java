package com.feature_flag.platform.common.exception;

public class InvalidTokenException extends UnauthorizedException {

    public InvalidTokenException(String message) {
        super(message, "INVALID_TOKEN");
    }
}