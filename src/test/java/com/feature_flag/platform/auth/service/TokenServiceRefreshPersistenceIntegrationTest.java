package com.feature_flag.platform.auth.service;

import com.feature_flag.platform.auth.dto.RefreshRequest;
import com.feature_flag.platform.auth.entity.RefreshToken;
import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.enums.UserStatus;
import com.feature_flag.platform.auth.repository.RefreshTokenRepository;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.exception.InvalidTokenException;
import com.feature_flag.platform.common.exception.UserSuspendedException;
import com.feature_flag.platform.common.security.TokenHashUtil;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the containment writes performed by {@code TokenServiceImpl.refresh} actually reach the
 * database.
 *
 * <p>These tests deliberately run <em>without</em> {@code @Transactional}. A test-managed transaction
 * would wrap the service call in an outer transaction that never commits or rolls back during the test,
 * so a rollback inside the service would be invisible and the bug these tests pin would pass unnoticed —
 * exactly as the pure-Mockito {@link TokenServiceTest} does, where {@code verify(...)} only proves the
 * call was issued, not that it survived.
 *
 * <p>The bug: {@code refresh} was {@code @Transactional} and performed the replay/expiry revocation and
 * then threw {@code InvalidTokenException}, an unchecked exception. Spring's default rollback rule
 * discarded the revocation, so a detected replay revoked nothing while the API still answered "possible
 * replay detected".
 */
@SpringBootTest
@ActiveProfiles("test")
class TokenServiceRefreshPersistenceIntegrationTest {

    @Autowired private TokenService tokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;

    private User user;
    private Tenant tenantOne;
    private Tenant tenantTwo;

    @BeforeEach
    void setUp() {
        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name()).orElseThrow();

        user = userRepository.save(User.builder()
                .email("replay-victim@test.com")
                .passwordHash("hash")
                .firstName("Vic")
                .status(UserStatus.ACTIVE)
                .build());

        tenantOne = tenantRepository.save(Tenant.builder().name("Tenant One").build());
        tenantTwo = tenantRepository.save(Tenant.builder().name("Tenant Two").build());

        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenantOne).status(MembershipStatus.ACTIVE)
                .roles(Set.of(ownerRole)).build());
        membershipRepository.save(Membership.builder()
                .user(user).tenant(tenantTwo).status(MembershipStatus.ACTIVE)
                .roles(Set.of(ownerRole)).build());
    }

    @AfterEach
    void tearDown() {
        List<RefreshToken> tokens = refreshTokenRepository.findAll();
        tokens.forEach(token -> token.setReplacedBy(null));
        refreshTokenRepository.saveAll(tokens);
        refreshTokenRepository.deleteAll();
        membershipRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    private RefreshToken storeToken(String rawToken, Tenant tenant, boolean revoked, Instant expiresAt) {
        return refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tenant(tenant)
                .tokenHash(TokenHashUtil.sha256(rawToken))
                .expiresAt(expiresAt)
                .revoked(revoked)
                .build());
    }

    private boolean isRevokedInDatabase(RefreshToken token) {
        return refreshTokenRepository.findById(token.getId()).orElseThrow().isRevoked();
    }

    /**
     * The core regression: replaying an already-rotated token must leave the attacker's live sibling
     * token revoked in the database, not merely attempt the revocation inside a doomed transaction.
     */
    @Test
    void replayDetected_revokesSiblingTokensInDatabase() {
        RefreshToken replayed = storeToken("stolen-and-already-used", tenantOne, true, Instant.now().plusSeconds(3600));
        RefreshToken attackerToken = storeToken("attacker-rotated", tenantOne, false, Instant.now().plusSeconds(3600));

        assertThrows(InvalidTokenException.class,
                () -> tokenService.refresh(new RefreshRequest("stolen-and-already-used")));

        assertTrue(isRevokedInDatabase(attackerToken),
                "replay detection must persist the revocation of the attacker's rotated token");
        assertTrue(isRevokedInDatabase(replayed));
    }

    /**
     * A confirmed replay means the user's refresh credential is compromised, so every session of that
     * user dies — not only the ones minted for the tenant the replayed token happened to carry.
     */
    @Test
    void replayDetected_revokesTokensOfEveryTenantForThatUser() {
        RefreshToken replayed = storeToken("stolen-tenant-one", tenantOne, true, Instant.now().plusSeconds(3600));
        RefreshToken otherTenantToken = storeToken("live-tenant-two", tenantTwo, false, Instant.now().plusSeconds(3600));
        RefreshToken tenantLessToken = storeToken("live-tenant-less", null, false, Instant.now().plusSeconds(3600));

        assertThrows(InvalidTokenException.class,
                () -> tokenService.refresh(new RefreshRequest("stolen-tenant-one")));

        assertTrue(isRevokedInDatabase(otherTenantToken),
                "a confirmed replay must revoke the user's sessions in every tenant");
        assertTrue(isRevokedInDatabase(tenantLessToken),
                "a confirmed replay must revoke the user's tenant-less sessions too");
        assertTrue(isRevokedInDatabase(replayed));
    }

    /**
     * Tokens minted by register, and by login for a user with no active membership, carry no tenant.
     * Replaying one used to be a complete no-op.
     */
    @Test
    void replayOfTenantLessToken_stillRevokesTheFamily() {
        RefreshToken replayed = storeToken("tenant-less-stolen", null, true, Instant.now().plusSeconds(3600));
        RefreshToken sibling = storeToken("tenant-less-sibling", null, false, Instant.now().plusSeconds(3600));

        assertThrows(InvalidTokenException.class,
                () -> tokenService.refresh(new RefreshRequest("tenant-less-stolen")));

        assertTrue(isRevokedInDatabase(sibling),
                "replay of a tenant-less token must still revoke the family");
    }

    /** The expired branch revoked the presented token and then threw, discarding the revocation. */
    @Test
    void expiredToken_persistsItsOwnRevocation() {
        RefreshToken expired = storeToken("long-expired", tenantOne, false, Instant.now().minusSeconds(60));

        assertThrows(InvalidTokenException.class,
                () -> tokenService.refresh(new RefreshRequest("long-expired")));

        assertTrue(isRevokedInDatabase(expired),
                "an expired token must be marked revoked in the database");
    }

    /** Suspension has to be enforceable without deleting rows by hand. */
    @Test
    void suspendedUser_cannotRefresh() {
        RefreshToken token = storeToken("suspended-user-token", tenantOne, false, Instant.now().plusSeconds(3600));
        user.setStatus(UserStatus.SUSPENDED);
        userRepository.save(user);

        assertThrows(UserSuspendedException.class,
                () -> tokenService.refresh(new RefreshRequest("suspended-user-token")));

        assertEquals(1, refreshTokenRepository.count(), "no replacement token may be minted for a suspended user");
        assertFalse(isRevokedInDatabase(token), "the presented token is neither rotated nor consumed");
    }

    /** The happy path must still commit its rotation. */
    @Test
    void validToken_rotatesAndCommits() {
        RefreshToken original = storeToken("healthy-token", tenantOne, false, Instant.now().plusSeconds(3600));

        tokenService.refresh(new RefreshRequest("healthy-token"));

        assertTrue(isRevokedInDatabase(original), "the consumed token is revoked");
        assertEquals(2, refreshTokenRepository.count(), "a replacement token is persisted");
    }
}
