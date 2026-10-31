package com.feature_flag.platform.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI document is what the dashboard generates its TypeScript types from, so "it is reachable
 * without a token, and it describes the real endpoints" is the whole contract.
 *
 * <p>{@code docker} activates {@link OpenApiConfig}; {@code test} is listed last so its in-memory
 * datasource wins over the one the docker profile expects.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"docker", "test"})
class OpenApiDocumentIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("the document is served without credentials and describes the API")
    void documentIsServedAndDescribesTheApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/features']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer").exists())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-API-Key"));
    }

    /**
     * The point of the whole exercise: ids the dashboard reads back are strings in the generated types.
     * If someone turns AuthResponse.tenantId back into a Long, this fails here rather than silently in a
     * browser.
     */
    @Test
    @DisplayName("ids on the dashboard surface are described as strings")
    void idsAreDescribedAsStrings() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // ["string", "null"]: a caller with no default tenant gets null.
                .andExpect(jsonPath("$.components.schemas.AuthResponse.properties.tenantId.type", hasItem("string")))
                .andExpect(jsonPath("$.components.schemas.AuthResponse.properties.userId.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.MemberResponse.properties.membershipId.type").value("string"));
    }

    /**
     * The other direction. Path variables and request fields stay {@code Long} on the server, but a client
     * generated from a document that calls them int64 would hold a TSID in a JavaScript number to send it.
     */
    @Test
    @DisplayName("ids the dashboard sends are described as strings, and durations stay numbers")
    void sentIdsAreDescribedAsStrings() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/features/{featureId}'].get.parameters[?(@.name == 'featureId')].schema.type",
                        hasItem("string")))
                .andExpect(jsonPath("$.components.schemas.FeatureEnableRequest.properties.envId.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.LoginRequest.properties.tenantId.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.AuthResponse.properties.accessTokenExpiresIn.type").value("integer"));
    }

    /**
     * Without this every response field is optional in the generated types, and the dashboard guards
     * fields the server always sends. Only fields marked nullable may be null or absent.
     */
    @Test
    @DisplayName("response fields are required unless marked nullable, and request schemas are left alone")
    void responseFieldsAreRequiredUnlessNullable() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.FeatureResponse.required", containsInAnyOrder("id", "key", "createdAt")))
                .andExpect(jsonPath("$.components.schemas.FeatureResponse.properties.description.type",
                        containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.AuthResponse.required", not(hasItem("refreshToken"))))
                .andExpect(jsonPath("$.components.schemas.InvitationResponse.properties.authResponse.oneOf[0]['$ref']")
                        .value("#/components/schemas/AuthResponse"))
                .andExpect(jsonPath("$.components.schemas.InvitationResponse.properties.authResponse.oneOf[1].type").value("null"))
                .andExpect(jsonPath("$.components.schemas.CreateFeatureRequest.required", contains("key")));
    }
}
