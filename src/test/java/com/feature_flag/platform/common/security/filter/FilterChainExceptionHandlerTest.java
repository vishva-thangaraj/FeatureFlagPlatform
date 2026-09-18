package com.feature_flag.platform.common.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * All five security filters sit far ahead of Spring Security's {@code ExceptionTranslationFilter}, and any
 * filter is ahead of the {@code DispatcherServlet} and therefore of {@code @RestControllerAdvice}. A
 * {@code RuntimeException} escaping one of them — a malformed {@code sub} claim, a
 * {@code DataAccessException} out of the membership lookup — reached neither handler and surfaced as the
 * container's own HTML error page: wrong content type, wrong shape, and a stack trace in the body.
 */
class FilterChainExceptionHandlerTest {

    private FilterChainExceptionHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        handler = new FilterChainExceptionHandler(new FilterErrorWriter(objectMapper));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/features");
        request.setRemoteAddr("198.51.100.4");
        return request;
    }

    @Test
    void aRuntimeExceptionFromAFilterBecomesTheStandardErrorShape() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain exploding = (req, res) -> {
            throw new DataAccessResourceFailureException("the database went away");
        };

        handler.doFilter(request(), response, exploding);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("application/json");

        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(500);
        assertThat(body.get("error").asText()).isEqualTo("Internal Server Error");
        assertThat(body.get("timestamp").isNull()).isFalse();
        assertThat(body.get("message").asText())
                .as("the cause is logged, never echoed to the caller")
                .isEqualTo("An unexpected error occurred.")
                .doesNotContain("database");
    }

    /** The exact failure named in the audit: Long.parseLong on a token subject that is not a number. */
    @Test
    void aNumberFormatExceptionFromTheJwtFilterBecomesTheStandardErrorShape() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain exploding = (req, res) -> {
            throw new NumberFormatException("For input string: \"not-a-user-id\"");
        };

        handler.doFilter(request(), response, exploding);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(objectMapper.readTree(response.getContentAsString()).get("message").asText())
                .isEqualTo("An unexpected error occurred.");
    }

    /** A request that failed nowhere must pass through completely untouched. */
    @Test
    void aSuccessfulRequestIsUnaffected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        handler.doFilter(request(), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).isNotNull();
    }

    /**
     * Once the body is on the wire there is nothing left to rewrite; swallowing the failure there would
     * hide a half-written response behind a 200.
     */
    @Test
    void anExceptionAfterTheResponseIsCommittedIsRethrown() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain exploding = (req, res) -> {
            res.getWriter().write("half a response");
            ((MockHttpServletResponse) res).setCommitted(true);
            throw new IllegalStateException("too late");
        };

        assertThatThrownBy(() -> handler.doFilter(request(), response, exploding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("too late");
    }
}
