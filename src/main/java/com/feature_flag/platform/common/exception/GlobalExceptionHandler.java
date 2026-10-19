package com.feature_flag.platform.common.exception;

import com.feature_flag.platform.common.dto.ErrorResponse;
import com.feature_flag.platform.common.dto.ValidationError;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.ObjectError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single place where every exception that escapes a controller is turned into the {@link ErrorResponse}
 * shape the API promises everywhere else.
 *
 * <p>Two rules hold for everything in here:
 *
 * <ol>
 *   <li><strong>Nothing internal reaches the client.</strong> Constraint names, SQL fragments, stack
 *       traces and Hibernate/JDBC wording are logged server-side and replaced with a flat, stable
 *       sentence in the response body. The status code carries the meaning; the message is for humans,
 *       not for probing the schema.</li>
 *   <li><strong>Nothing escapes unlogged.</strong> The {@link Exception} fallback exists so that an
 *       unmapped failure is still answered in our own shape <em>and</em> still lands in the log with its
 *       stack trace, instead of being rendered by Spring's {@code BasicErrorController} in a different
 *       JSON shape that this class never sees.</li>
 * </ol>
 *
 * <p>Note the deliberate absence of an {@code IllegalStateException} handler. It used to map to 409,
 * which meant any {@code IllegalStateException} raised inside the JDK, Hibernate or Spring — "Session is
 * closed", "response already committed", and friends — was reported to the caller as a business conflict,
 * complete with its internal message. Domain conflicts throw {@link ConflictException} instead, which
 * {@link BaseException} already carries to 409; anything else is a bug and belongs in the 500 fallback.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Client-facing text for a uniqueness/FK violation. Deliberately says nothing about the constraint. */
    private static final String CONFLICT_MESSAGE =
            "The request conflicts with the current state of the resource. It may already exist, or have been modified concurrently.";

    /** Client-facing text for anything unmapped. Deliberately says nothing at all. */
    private static final String INTERNAL_ERROR_MESSAGE =
            "An unexpected error occurred while processing the request.";

    private static final String MALFORMED_BODY_MESSAGE =
            "Malformed request body: the request could not be parsed as valid JSON matching the expected structure.";

    private static final String VALIDATION_FAILED_MESSAGE = "Request validation failed";

    // ------------------------------------------------------------------ domain

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ErrorResponse> handleBaseException(BaseException ex) {
        HttpStatus status = ex.getHttpStatus();
        if (status.is5xxServerError()) {
            log.error("Domain exception [{}] -> {}", ex.getErrorCode(), status, ex);
        } else {
            log.debug("Domain exception [{}] -> {}: {}", ex.getErrorCode(), status, ex.getMessage());
        }
        return buildResponse(status, ex.getMessage());
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Every check-then-act uniqueness guard in this codebase ("does the feature key exist? no — insert it")
     * has a window between the check and the insert. Two concurrent requests, or one request racing a
     * database-level unique index the service never checked, land here. That is a 409, not a 500: the
     * caller's request was well-formed and the correct remedy is to retry or pick a different value.
     *
     * <p>The raw message is logged, never returned — it carries the constraint name, the table and often
     * the offending values, which is schema disclosure.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation -> 409 Conflict: {}", mostSpecificMessage(ex), ex);
        return buildResponse(HttpStatus.CONFLICT, CONFLICT_MESSAGE);
    }

    // ------------------------------------------------------------------ request binding / validation

    /**
     * Body-level bean validation. The previous version read {@code getFieldErrors()} only, so a
     * class-level constraint (one annotated on the DTO type rather than on a field — cross-field checks
     * such as "start must precede end") produced a 400 with an empty message: the client was told it was
     * wrong and nothing else. Global errors are included now.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> messages = new ArrayList<>();
        List<ValidationError> fieldErrors = new ArrayList<>();

        ex.getBindingResult().getFieldErrors().forEach(fe -> {
            messages.add(fe.getField() + ": " + fe.getDefaultMessage());
            fieldErrors.add(new ValidationError(fe.getField(), fe.getDefaultMessage()));
        });
        ex.getBindingResult().getGlobalErrors().forEach(ge -> {
            messages.add(globalErrorText(ge));
            // Null field: a class-level constraint belongs to the request, not to one input.
            fieldErrors.add(new ValidationError(null, ge.getDefaultMessage()));
        });

        String message = messages.isEmpty() ? VALIDATION_FAILED_MESSAGE : String.join(", ", messages);
        log.debug("Request validation failed -> 400: {}", message);
        return buildResponse(HttpStatus.BAD_REQUEST, message, fieldErrors);
    }

    /** Validation on {@code @Validated} controller method parameters / path and request params. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        if (ex.getConstraintViolations() == null || ex.getConstraintViolations().isEmpty()) {
            log.debug("Constraint violation -> 400: {}", VALIDATION_FAILED_MESSAGE);
            return buildResponse(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_MESSAGE, List.of());
        }

        String message = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining(", "));
        List<ValidationError> fieldErrors = ex.getConstraintViolations().stream()
                .map(cv -> new ValidationError(leafOf(cv.getPropertyPath()), cv.getMessage()))
                .toList();

        log.debug("Constraint violation -> 400: {}", message);
        return buildResponse(HttpStatus.BAD_REQUEST, message, fieldErrors);
    }

    /** Spring 6.1 method-validation failures (e.g. {@code @RequestParam @Min(1) int page}). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        List<String> messages = new ArrayList<>();
        List<ValidationError> fieldErrors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error -> {
            String text = error.getDefaultMessage();
            String name = result.getMethodParameter().getParameterName();
            messages.add(name == null ? String.valueOf(text) : name + ": " + text);
            fieldErrors.add(new ValidationError(name, text));
        }));

        String message = messages.isEmpty() ? VALIDATION_FAILED_MESSAGE : String.join(", ", messages);
        log.debug("Method validation failed -> 400: {}", message);
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    /** Unparseable or absent request body. A client error, not a server one. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        // The parse detail names our internal DTO classes and field paths — log it, do not return it.
        log.debug("Unreadable request body -> 400: {}", mostSpecificMessage(ex));
        return buildResponse(HttpStatus.BAD_REQUEST, MALFORMED_BODY_MESSAGE);
    }

    /**
     * {@code GET /api/v1/members/abc} against a {@code @PathVariable Long}. The caller sent nonsense;
     * answering 500 blamed the server for it.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String required = ex.getRequiredType() == null ? "the expected type" : ex.getRequiredType().getSimpleName();
        String message = "Invalid value for '" + ex.getName() + "': expected " + required + ".";
        log.debug("Type mismatch -> 400: {}", message);
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        String message = "Required request parameter '" + ex.getParameterName() + "' is missing.";
        log.debug("Missing request parameter -> 400: {}", message);
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String message = "HTTP method " + ex.getMethod() + " is not supported for this endpoint.";
        log.debug("Method not allowed -> 405: {}", message);
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, message);
    }

    // ------------------------------------------------------------------ security

    /**
     * Thrown by method security ({@code @PreAuthorize}) once the request is already inside the dispatcher,
     * where Spring Security's filter-chain {@code AccessDeniedHandler} no longer sees it. Without this it
     * escaped as a 500 — telling an unauthorised caller the server had broken, and logging it as a defect.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied -> 403: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }

    // ------------------------------------------------------------------ routing

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        log.debug("No handler -> 404: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "The requested resource was not found.");
    }

    /**
     * Anything raised as an explicit {@link ResponseStatusException} already knows its own status; honour
     * it rather than letting the 500 fallback flatten it.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("ResponseStatusException -> {}", status, ex);
            return buildResponse(status, INTERNAL_ERROR_MESSAGE);
        }
        String reason = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        log.debug("ResponseStatusException -> {}: {}", status, reason);
        return buildResponse(status, reason);
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> handleErrorResponse(ErrorResponseException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("ErrorResponseException -> {}", status, ex);
            return buildResponse(status, INTERNAL_ERROR_MESSAGE);
        }
        log.debug("ErrorResponseException -> {}", status);
        return buildResponse(status, status.getReasonPhrase());
    }

    // ------------------------------------------------------------------ coding errors

    /**
     * A 500 is the right status — this only fires when a {@code @PathVariable} name does not match the
     * mapping template, which no client can cause and no client can fix. It is logged at ERROR precisely
     * because it is always our bug, and it would otherwise be indistinguishable from the generic fallback.
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<ErrorResponse> handleMissingPathVariable(MissingPathVariableException ex) {
        log.error("MAPPING BUG: path variable '{}' is declared on the handler method but absent from the "
                        + "URI template — the @PathVariable name and the mapping do not agree. Fix the controller.",
                ex.getVariableName(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------ fallback

    /**
     * Last resort. Without it, anything unmapped fell through to Spring's {@code BasicErrorController},
     * which answers in {@code {timestamp,status,error,path}} — a second, undocumented error shape — and
     * never passes through this class, so the failure was never logged here either.
     *
     * <p>The client gets a status and a flat sentence. The stack trace goes to the log, where it belongs.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception -> 500 Internal Server Error", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------ helpers

    private static String globalErrorText(ObjectError error) {
        String detail = error.getDefaultMessage();
        return detail == null || detail.isBlank()
                ? error.getObjectName() + ": invalid"
                : error.getObjectName() + ": " + detail;
    }

    /** Unwraps to the innermost cause's message for logging; never used in a response body. */
    private static String mostSpecificMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String message) {
        ErrorResponse error = new ErrorResponse(status.value(), status.getReasonPhrase(), message, Instant.now());
        return ResponseEntity.status(status).body(error);
    }

    /**
     * As above, plus the per-field breakdown. Only validation handlers pass one: everywhere else the error
     * is not about a particular input, and {@code fieldErrors} is omitted from the body entirely rather
     * than sent as an empty list that a client would have to distinguish from a real one.
     */
    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String message,
                                                        List<ValidationError> fieldErrors) {
        ErrorResponse error = new ErrorResponse(
                status.value(), status.getReasonPhrase(), message, Instant.now(), fieldErrors);
        return ResponseEntity.status(status).body(error);
    }

    /**
     * The last node of a constraint's property path. Jakarta reports it as the whole invocation --
     * {@code updateEnv.request.name} -- and only the tail names something a form has an input for.
     */
    private static String leafOf(Path propertyPath) {
        String text = String.valueOf(propertyPath);
        int lastDot = text.lastIndexOf('.');
        String leaf = lastDot < 0 ? text : text.substring(lastDot + 1);
        return leaf.isBlank() ? null : leaf;
    }
}
