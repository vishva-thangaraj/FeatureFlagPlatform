package com.feature_flag.platform.common.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends BaseException {

    public ConflictException(String message) {
        super(message, "CONFLICT", HttpStatus.CONFLICT);
    }

    public ConflictException(String message, String errorCode) {
        super(message, errorCode, HttpStatus.CONFLICT);
    }
}