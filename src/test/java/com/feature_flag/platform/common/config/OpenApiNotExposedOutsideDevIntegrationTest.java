package com.feature_flag.platform.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The other half of {@link OpenApiConfig}'s contract: a profile that has not opted in does not publish a
 * map of the API. {@code test} stands in for prod and staging here — none of them activate OpenApiConfig,
 * so none of them carry the permit rule, and {@code springdoc.api-docs.enabled} stays false for all three.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiNotExposedOutsideDevIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("the document is not readable on a profile that did not opt in")
    void documentIsNotServed() throws Exception {
        int status = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus();

        // 401 (the main chain demanding a token) and 404 (springdoc disabled, so nothing is mapped) are
        // both acceptable; what must never happen is handing the document out.
        assertNotEquals(200, status, "the OpenAPI document must not be served outside dev/docker");
    }
}
