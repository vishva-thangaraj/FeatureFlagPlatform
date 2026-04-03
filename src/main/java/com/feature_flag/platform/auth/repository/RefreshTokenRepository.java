package com.feature_flag.platform.auth.repository;

import com.feature_flag.platform.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndTenantIdAndRevokedFalse(Long userId, Long tenantId);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user.id = :userId AND rt.tenant.id = :tenantId AND rt.revoked = false")
    void revokeAllByUserIdAndTenantId(Long userId, Long tenantId);
}