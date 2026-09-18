package com.feature_flag.platform.tenant.repository;

import com.feature_flag.platform.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The tenant-scoped slice of {@code refresh_tokens}, used by nothing but tenant deletion.
 *
 * <p>{@code refresh_tokens.tenant_id} is a foreign key to {@code tenants} — every login and every
 * rotation writes one — so a tenant whose owner has ever signed in cannot be deleted until these rows
 * are gone. That makes session cleanup part of the tenant cascade even though the rows belong to the
 * auth module. It is exposed here, rather than as two more methods on
 * {@code auth.repository.RefreshTokenRepository}, purely to keep this fix inside the tenant and feature
 * modules; moving the two queries next to the rest of the refresh-token access is a safe follow-up.
 *
 * <p>Deleting rather than detaching is deliberate: a refresh token names the tenant its access tokens
 * will be minted for, so once that tenant is gone the credential can only ever fail. Leaving it alive
 * with a dangling tenant would keep a session that resolves to nothing.
 */
public interface TenantRefreshTokenRepository extends Repository<RefreshToken, Long> {

    /**
     * Breaks the {@code replaced_by} self-reference before the rows are deleted.
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshToken rt where rt.tenant.id = :tenantId")
    int deleteAllByTenantId(@Param("tenantId") Long tenantId);
}
