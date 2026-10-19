package com.feature_flag.platform.common.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feature_flag.platform.auth.dto.AuthResponse;
import com.feature_flag.platform.auth.dto.LoginRequest;
import com.feature_flag.platform.auth.dto.SwitchTenantRequest;
import com.feature_flag.platform.feature.dto.FeatureEnableRequest;
import com.feature_flag.platform.tenant.dto.MemberResponse;
import com.feature_flag.platform.tenant.dto.RoleResponse;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JSON shape of 64-bit ids on the surface a browser consumes.
 *
 * <p>Every entity here draws its id from {@code BaseEntity}'s {@code @Tsid} generator, so ids are around
 * 4.6e17. A JavaScript {@code Number} is exact only to 2^53 ≈ 9.0e15, so an id sent as a JSON number is
 * silently rounded by {@code JSON.parse} — producing an id that addresses nothing, with no error anywhere
 * to say so. Responses therefore send ids as strings.
 *
 * <p>Request bodies deliberately keep {@code Long}, because Jackson coerces a quoted number to a numeric
 * target by default. That default is what lets the dashboard hold every id as a string and send it back
 * unchanged, so it is pinned here too: turning coercion off would break every write the dashboard makes,
 * and this test is the thing that would notice.
 */
class TsidJsonContractTest {

    /** A real TSID, past 2^53 - 1 = 9007199254740991. */
    private static final long TSID = 461168601842738689L;
    private static final String TSID_TEXT = "461168601842738689";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tsidExceedsJavaScriptSafeIntegerRange() {
        assertTrue(TSID > 9007199254740991L,
                "the fixture must be past 2^53 or this whole test proves nothing");
    }

    @Test
    void authResponse_sendsIdsAsJsonStrings() throws Exception {
        AuthResponse response = new AuthResponse(
                "access", "refresh", TSID_TEXT, "user@test.com", TSID_TEXT, List.of("OWNER"), 900_000L);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertTrue(json.get("userId").isTextual(), "userId must be a JSON string, not a number");
        assertTrue(json.get("tenantId").isTextual(), "tenantId must be a JSON string, not a number");
        assertEquals(TSID_TEXT, json.get("tenantId").asText());
    }

    @Test
    void memberResponse_sendsIdsAsJsonStrings() throws Exception {
        MemberResponse member = new MemberResponse(
                TSID_TEXT, TSID_TEXT, "jane@test.com", "Jane", "Doe",
                List.of(new RoleResponse(TSID_TEXT, "OWNER")), MembershipStatus.ACTIVE);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(member));

        assertTrue(json.get("membershipId").isTextual());
        assertTrue(json.get("userID").isTextual());
        assertTrue(json.get("roles").get(0).get("id").isTextual());
        assertEquals(TSID_TEXT, json.get("membershipId").asText());
    }

    @Test
    void requestBodies_acceptQuotedNumbers() throws Exception {
        SwitchTenantRequest switchTenant =
                mapper.readValue("{\"tenantId\":\"" + TSID_TEXT + "\"}", SwitchTenantRequest.class);
        LoginRequest login = mapper.readValue(
                "{\"email\":\"u@t.com\",\"password\":\"p\",\"tenantId\":\"" + TSID_TEXT + "\"}",
                LoginRequest.class);
        FeatureEnableRequest enable = mapper.readValue(
                "{\"envId\":\"" + TSID_TEXT + "\",\"enabled\":true,\"rolloutPercentage\":25}",
                FeatureEnableRequest.class);

        assertEquals(TSID, switchTenant.tenantId());
        assertEquals(TSID, login.tenantId());
        assertEquals(TSID, enable.envId());
    }

    @Test
    void requestBodies_stillAcceptUnquotedNumbers() throws Exception {
        SwitchTenantRequest request =
                mapper.readValue("{\"tenantId\":" + TSID_TEXT + "}", SwitchTenantRequest.class);

        assertEquals(TSID, request.tenantId(), "non-browser clients send a bare number and must keep working");
    }
}
