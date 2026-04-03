package com.feature_flag.platform.common.exception;

public class EmailAlreadyExistsException extends ConflictException {

    public EmailAlreadyExistsException(String message) {
        super(message, "EMAIL_ALREADY_EXISTS");
    }
}