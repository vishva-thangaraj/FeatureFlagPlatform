package com.feature_flag.platform.common.dto;

/**
 * One failed constraint, addressed to the input that failed it.
 *
 * <p>{@code field} is null for an error that belongs to the request as a whole rather than to any one
 * input — a class-level constraint such as "start must precede end". A form should render those at the
 * top rather than hunting for an input to attach them to.
 */
public record ValidationError(
        String field,
        String message
) {
}
