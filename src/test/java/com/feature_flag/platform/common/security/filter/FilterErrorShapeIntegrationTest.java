package com.feature_flag.platform.common.security.filter;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.security.JwtProperties;
import com.feature_flag.platform.common.security.JwtTokenProvider;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Errors thrown from inside the security filters must reach the client in the API's standard
 * {@code ErrorResponse} shape.
 *
 * <p>The five filters sit at {@code UsernamePasswordAuthenticationFilter ± 2}, well ahead of Spring
 * Security's {@code ExceptionTranslationFilter}, and ahead of the {@code DispatcherServlet} and therefore
 * of {@code @RestControllerAdvice}. Anything they threw — a {@code DataAccessException} from
 * {@code TenantMembershipResolver}, a {@code NumberFormatException} on a token's {@code sub} claim —
 * bypassed both and came back as the servlet container's own error page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FilterErrorShapeIntegrationTest {

    private static final AtomicInteger CLIENT_ADDRESS_SEQUENCE = new AtomicInteger();

    /** Stubbed so the membership lookup inside TenantContextFilter fails the way a database blip would. */
    @MockitoBean private TenantMembershipResolver tenantMembershipResolver;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;

    private String token;

    @BeforeEach
    void setUp() {
        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();
        User user = userRepository.save(User.builder()
                .email("shape@test.com").firstName("Shape").passwordHash("hash")
                .status(UserStatus.ACTIVE).build());
        Tenant tenant = tenantRepository.save(Tenant.builder().name("Shape Org").build());
        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenant).status(MembershipStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(ownerRole))).build());

        token = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), tenant.getId(), List.of(RoleName.OWNER.name()));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder, String bearer) {
        return builder
                .header("Authorization", "Bearer " + bearer)
                .with(request -> {
                    request.setRemoteAddr("192.0.2." + CLIENT_ADDRESS_SEQUENCE.incrementAndGet());
                    return request;
                });
    }

    @Test
    void anUnexpectedFailureInsideAFilterIsAnErrorResponse_notAContainerErrorPage() throws Exception {
        when(tenantMembershipResolver.requireActiveMembership(anyLong(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));

        mockMvc.perform(authorized(get("/api/v1/features"), token))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.timestamp").exists())
                // The cause is logged, not handed to the caller.
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
    }

    /**
     * A well-signed token whose {@code sub} is not a number used to blow up on
     * {@code Long.parseLong(claims.getSubject())} inside the filter. It is an unusable credential, not a
     * server fault, so it must be refused as one.
     */
    @Test
    void aTokenWithANonNumericSubjectIsRefused_notA500() throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.getSecret());
        String forged = Jwts.builder()
                .setSubject("not-a-user-id")
                .claim("email", "shape@test.com")
                .claim("roles", List.of("OWNER"))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(keyBytes), SignatureAlgorithm.HS256)
                .compact();

        mockMvc.perform(authorized(get("/api/v1/features"), forged))
                .andExpect(status().is4xxClientError());
    }
}
