package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.common.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Writes an {@link ErrorResponse} body from inside a servlet filter.
 *
 * <p>Filters run before {@code @RestControllerAdvice}, so they cannot route their errors through
 * GlobalExceptionHandler. Each filter used to hand-roll a JSON string, which drifted from the shape every
 * other error in the API uses — this keeps them identical.
 */
@Component
public class FilterErrorWriter {

    private final ObjectMapper objectMapper;

    public FilterErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                new ErrorResponse(status.value(), status.getReasonPhrase(), message, Instant.now()));
    }
}
