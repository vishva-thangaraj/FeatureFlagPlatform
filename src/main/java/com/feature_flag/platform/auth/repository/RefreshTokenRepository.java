package com.feature_flag.platform.auth.repository;

import com.feature_flag.platform.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndTenantIdAndRevokedFalse(Long userId, Long tenantId);

    /**
     * Revokes every live refresh token of a user, in a transaction of its own.
     *
     * <p>Called when a replay is detected, i.e. from a request that is about to fail with 401. The
     * caller's transaction is therefore doomed to roll back, and a revocation enlisted in it would be
     * silently discarded — the containment would never reach the database. {@code REQUIRES_NEW}
     * suspends the caller's transaction and commits this UPDATE independently, so the revocation holds
     * regardless of how the request ends.
     *
     * <p>The scope is the whole user rather than one tenant: a replay is proof that the refresh
     * credential leaked, and there is no reason to believe the thief's copy stops at the tenant the
     * replayed token happened to carry.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user.id = :userId AND rt.revoked = false")
    int revokeAllByUserIdInNewTransaction(@Param("userId") Long userId);

    /**
     * Revokes a single refresh token in a transaction of its own; see
     * {@link #revokeAllByUserIdInNewTransaction(Long)} for why the separate transaction is required.
     * Used to burn an expired token as it is rejected.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.id = :tokenId AND rt.revoked = false")
    int revokeByIdInNewTransaction(@Param("tokenId") Long tokenId);

    /**
     * Breaks the {@code replaced_by} self-reference before a tenant's refresh tokens are deleted.
     *
     * <p>Rotation leaves every superseded token pointing at its replacement. A single multi-row delete
     * checks that self-FK row by row, so whether it survives depends on the order the database happens to
     * remove them in; nulling the pointers first makes the delete order-independent. It also releases any
     * token outside the tenant that points into it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken rt set rt.replacedBy = null
            where rt.replacedBy.id in (select r.id from RefreshToken r where r.tenant.id = :tenantId)
            """)
    int detachReplacementChainsByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Tenant-deletion cascade. {@code refresh_tokens.tenant_id} is a foreign key to {@code tenants} and
     * every login and rotation writes one, so a tenant whose owner has ever signed in cannot be deleted
     * until these rows are gone.
     *
     * <p>Deleting rather than detaching is deliberate: a refresh token names the tenant its access tokens
     * will be minted for, so once that tenant is gone the credential can only ever fail. Leaving it alive
     * with a dangling tenant would keep a session that resolves to nothing.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshToken rt where rt.tenant.id = :tenantId")
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}
