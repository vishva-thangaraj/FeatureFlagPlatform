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
}