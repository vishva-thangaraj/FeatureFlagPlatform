package com.feature_flag.platform.common.exception;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the contract of {@link GlobalExceptionHandler}: <em>every</em> failure leaving a controller is
 * answered in the {@link com.feature_flag.platform.common.dto.ErrorResponse} shape
 * ({@code status, error, message, timestamp}), with the right status and with nothing internal in the body.
 *
 * <p>Before this handler existed, anything unmapped fell through to Spring's {@code BasicErrorController},
 * which answers {@code {timestamp,status,error,path}} — a second, undocumented error shape that clients had
 * to parse as well, and which never passed through this class, so the failure was never logged here either.
 *
 * <p>Driven through a throwaway controller on a standalone {@code MockMvc} so the assertions are about the
 * handler and nothing else — no security chain, no database, no application context.
 */
class GlobalExceptionHandlerTest {

    /** The raw text a database driver would put in a constraint violation. Must never reach a client. */
    private static final String RAW_CONSTRAINT_DETAIL =
            "Duplicate entry 'acme-flag-7' for key 'uk_features_tenant_key'";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ------------------------------------------------------------------ 409, and no schema disclosure

    /**
     * The handler the rest of the remediation is built on. Every check-then-act uniqueness guard in the
     * codebase (feature key, environment name, membership, user email, feature-env-status, user override)
     * can lose the race between its {@code existsBy...} check and its insert; the loser used to get a 500.
     */
    @Test
    void dataIntegrityViolation_is409_inTheErrorResponseShape() throws Exception {
        mockMvc.perform(get("/boom/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value(HttpStatus.CONFLICT.getReasonPhrase()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                // the BasicErrorController shape must not appear
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    /**
     * Schema disclosure: the driver's message names the table, the unique index and the offending value.
     * It belongs in the server log, never in the response.
     */
    @Test
    void dataIntegrityViolation_doesNotLeakTheRawConstraintText() throws Exception {
        mockMvc.perform(get("/boom/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", not(containsString("uk_features_tenant_key"))))
                .andExpect(jsonPath("$.message", not(containsString("Duplicate entry"))))
                .andExpect(jsonPath("$.message", not(containsString("acme-flag-7"))))
                .andExpect(jsonPath("$.message", not(containsString("SQL"))))
                .andExpect(jsonPath("$.message", not(containsString("constraint"))));
    }

    // ------------------------------------------------------------------ 500 fallback

    @Test
    void unexpectedException_is500_inTheErrorResponseShape_withNoInternalDetail() throws Exception {
        mockMvc.perform(get("/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").doesNotExist())
                // neither the exception's own message nor any hint of a stack trace
                .andExpect(jsonPath("$.message", not(containsString("jdbc connection pool exhausted"))))
                .andExpect(jsonPath("$.message", not(containsString("BoomController"))))
                .andExpect(jsonPath("$.message", not(containsString("com.feature_flag"))))
                .andExpect(jsonPath("$.message", not(containsString("Exception"))))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    /**
     * {@code IllegalStateException} used to be mapped to 409 with its own message. It is thrown all over the
     * JDK, Hibernate and Spring, so that returned things like "Session/EntityManager is closed" to a client
     * as a business conflict. It must now be an ordinary 500 with a generic body.
     */
    @Test
    void illegalStateException_isNoLongerMappedToConflict() throws Exception {
        mockMvc.perform(get("/boom/illegal-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message", not(containsString("Session/EntityManager is closed"))));
    }

    /** Domain conflicts still answer 409 — with their own message, which is written for the client. */
    @Test
    void conflictException_stillAnswers409_withItsOwnMessage() throws Exception {
        mockMvc.perform(get("/boom/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("An invitation already exists for this email"));
    }

    // ------------------------------------------------------------------ 400s

    @Test
    void malformedJsonBody_is400_inTheErrorResponseShape() throws Exception {
        mockMvc.perform(post("/boom/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value(HttpStatus.BAD_REQUEST.getReasonPhrase()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    /** {@code GET /api/v1/members/abc} against a {@code @PathVariable Long} — the caller's mistake, not ours. */
    @Test
    void nonNumericPathVariableForALongParameter_is400_not500() throws Exception {
        mockMvc.perform(get("/boom/member/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    /**
     * The old validation handler read {@code getFieldErrors()} only, so a class-level constraint produced a
     * 400 whose {@code message} was the empty string: the client was told it was wrong and nothing else.
     */
    @Test
    void classLevelValidationError_yieldsANonEmptyMessage() throws Exception {
        mockMvc.perform(post("/boom/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        // passes the field-level @NotBlank, fails the class-level @NamesMustDiffer
                        .content("{\"name\":\"same\",\"other\":\"same\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.message", containsString("name and other must differ")));
    }

    @Test
    void fieldLevelValidationError_stillReportsTheField() throws Exception {
        mockMvc.perform(post("/boom/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"other\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("name")));
    }

    /**
     * The structured half of a validation failure. {@code message} keeps the joined sentence it always
     * carried -- this is additive -- but a form needs each complaint attached to the input that caused it,
     * and splitting that sentence back apart was the client's only option before.
     */
    @Test
    void fieldLevelValidationError_isAlsoReportedPerField() throws Exception {
        mockMvc.perform(post("/boom/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"other\":\"x\"}"))
                .andExpect(status().isBadRequest())
                // unchanged for anything already reading it
                .andExpect(jsonPath("$.message", containsString("name")))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')].message").isNotEmpty());
    }

    /** A class-level constraint belongs to the request, not to an input, so it carries no field. */
    @Test
    void classLevelValidationError_isReportedWithANullField() throws Exception {
        mockMvc.perform(post("/boom/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"same\",\"other\":\"same\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").doesNotExist())
                .andExpect(jsonPath("$.fieldErrors[0].message",
                        containsString("name and other must differ")));
    }

    /**
     * fieldErrors is omitted, not sent empty, when the error is not about input -- a client should not
     * have to tell an empty list apart from a real one.
     */
    @Test
    void nonValidationError_carriesNoFieldErrors() throws Exception {
        mockMvc.perform(get("/boom/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void constraintViolationException_is400() throws Exception {
        mockMvc.perform(get("/boom/constraint-violation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ------------------------------------------------------------------ 405 / 403

    @Test
    void wrongHttpMethod_is405_inTheErrorResponseShape() throws Exception {
        mockMvc.perform(post("/boom/unexpected"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value(HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    /**
     * Method security throws this from inside the dispatcher, past the filter chain's
     * {@code AccessDeniedHandler}. It used to escape as a 500.
     */
    @Test
    void accessDenied_is403_not500() throws Exception {
        mockMvc.perform(get("/boom/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value(HttpStatus.FORBIDDEN.getReasonPhrase()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ------------------------------------------------------------------ the throwaway controller

    @RestController
    @RequestMapping("/boom")
    static class BoomController {

        @GetMapping("/data-integrity")
        void dataIntegrity() {
            throw new DataIntegrityViolationException(
                    "could not execute statement", new RuntimeException(RAW_CONSTRAINT_DETAIL));
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new NullPointerException("jdbc connection pool exhausted");
        }

        @GetMapping("/illegal-state")
        void illegalState() {
            throw new IllegalStateException("Session/EntityManager is closed");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("An invitation already exists for this email");
        }

        @GetMapping("/access-denied")
        void accessDenied() {
            throw new org.springframework.security.access.AccessDeniedException("Access Denied");
        }

        @GetMapping("/constraint-violation")
        void constraintViolation() {
            throw new ConstraintViolationException("page must be at least 1", Set.of());
        }

        @GetMapping("/member/{id}")
        void member(@PathVariable("id") Long id) {
            // never reached for a non-numeric id
        }

        @PostMapping("/echo")
        void echo(@Valid @RequestBody EchoRequest request) {
            // never reached for an invalid body
        }
    }

    /** Carries both a field-level and a class-level constraint, so the two paths can be told apart. */
    @NamesMustDiffer
    public record EchoRequest(@NotBlank String name, String other) {
    }

    /**
     * A cross-field (class-level) constraint. Violating it produces a <em>global</em> error, not a field
     * error — exactly the case the old handler dropped on the floor.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = NamesMustDifferValidator.class)
    public @interface NamesMustDiffer {
        String message() default "name and other must differ";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class NamesMustDifferValidator implements ConstraintValidator<NamesMustDiffer, EchoRequest> {

        public NamesMustDifferValidator() {
        }

        @Override
        public boolean isValid(EchoRequest value, ConstraintValidatorContext context) {
            return value == null || value.name() == null || !value.name().equals(value.other());
        }
    }
}
