package com.feature_flag.platform.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The shape of every error this API returns.
 *
 * <p>{@code fieldErrors} is present only on validation failures, and is additive: {@code message} still
 * carries the same joined summary it always did, so nothing that reads it breaks. It exists because that
 * summary — {@code "key: must match ..., description: size must be ..."} — is a sentence, and a form that
 * wants to put each complaint under the input that caused it had no way to take it apart.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp,
        List<ValidationError> fieldErrors
) {

    /** Errors that are not about a specific input, which is most of them. */
    public ErrorResponse(int status, String error, String message, Instant timestamp) {
        this(status, error, message, timestamp, null);
    }
}
