package com.feature_flag.platform.tenant.service;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.auth.enums.RoleName;
import com.feature_flag.platform.auth.repository.RoleRepository;
import com.feature_flag.platform.auth.repository.UserRepository;
import com.feature_flag.platform.common.config.I18N;
import com.feature_flag.platform.common.config.Messages;
import com.feature_flag.platform.common.exception.ResourceNotFoundException;
import com.feature_flag.platform.common.security.TenantMembershipResolver;
import com.feature_flag.platform.common.security.TenantUserDetails;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.feature.repository.FeatureRuleRepository;
import com.feature_flag.platform.feature.repository.FeatureUserOverrideRepository;
import com.feature_flag.platform.tenant.dto.TenantCreationRequest;
import com.feature_flag.platform.tenant.dto.TenantList;
import com.feature_flag.platform.tenant.dto.TenantResponse;
import com.feature_flag.platform.tenant.dto.UpdateTenantRequest;
import com.feature_flag.platform.tenant.entity.Membership;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import com.feature_flag.platform.tenant.enums.TenantPlan;
import com.feature_flag.platform.tenant.mapper.TenantMapper;
import com.feature_flag.platform.tenant.repository.ApiKeyRepository;
import com.feature_flag.platform.tenant.repository.MembershipRepository;
import com.feature_flag.platform.auth.repository.RefreshTokenRepository;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import com.feature_flag.platform.tenant.repository.UserInvitationRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * {@code /api/v1/tenants/{tenantId}} is the one place where the tenant a request acts on comes from the
 * path rather than from {@code X-Tenant-Id}, so every single-tenant operation here authorizes the caller
 * against the <em>path</em> tenant: an ACTIVE membership in it to read, and the OWNER role in it to rename
 * or delete. {@code TenantContextFilter} now resolves the same path tenant before the declarative role
 * rules run; the checks below are deliberately duplicated so the service stays safe on its own.
 */
@Service
public class TenantServiceImpl implements TenantService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantServiceImpl.class);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final UserInvitationRepository userInvitationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final FeatureRuleRepository featureRuleRepository;
    private final FeatureUserOverrideRepository featureUserOverrideRepository;
    private final FeatureEnvStatusRepository featureEnvStatusRepository;
    private final FeatureRepository featureRepository;
    private final EnvRepository envRepository;
    private final TenantMembershipResolver tenantMembershipResolver;
    private final TenantMapper tenantMapper;

    public TenantServiceImpl(TenantRepository tenantRepository,
                             UserRepository userRepository,
                             MembershipRepository membershipRepository,
                             RoleRepository roleRepository,
                             ApiKeyRepository apiKeyRepository,
                             UserInvitationRepository userInvitationRepository,
                             RefreshTokenRepository refreshTokenRepository,
                             FeatureRuleRepository featureRuleRepository,
                             FeatureUserOverrideRepository featureUserOverrideRepository,
                             FeatureEnvStatusRepository featureEnvStatusRepository,
                             FeatureRepository featureRepository,
                             EnvRepository envRepository,
                             TenantMembershipResolver tenantMembershipResolver,
                             TenantMapper tenantMapper) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.userInvitationRepository = userInvitationRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.featureRuleRepository = featureRuleRepository;
        this.featureUserOverrideRepository = featureUserOverrideRepository;
        this.featureEnvStatusRepository = featureEnvStatusRepository;
        this.featureRepository = featureRepository;
        this.envRepository = envRepository;
        this.tenantMembershipResolver = tenantMembershipResolver;
        this.tenantMapper = tenantMapper;
    }

    @Override
    @Transactional
    public TenantResponse createTenant(@Valid TenantCreationRequest tenantRequest, TenantUserDetails userDetails) {
        Tenant savedTenant = tenantRepository.save(Tenant.builder()
                .name(tenantRequest.name())
                .plan(TenantPlan.FREE)
                .build());

        Long userId = userDetails.getUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_USER_NOT_FOUND)));

        Role ownerRole = roleRepository.findByName(RoleName.OWNER.name())
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_ROLE_NOT_FOUND, RoleName.OWNER.name())));

        Membership ownerMembership = Membership.builder()
                .tenant(savedTenant)
                .user(user)
                .roles(Set.of(ownerRole))
                .status(MembershipStatus.ACTIVE)
                .build();
        membershipRepository.save(ownerMembership);

        return tenantMapper.toTenantResponse(savedTenant, List.of(ownerRole));
    }

    @Override
    public TenantList getTenantsForUser(TenantUserDetails userDetails) {
        Long userId = userDetails.getUserId();
        List<Membership> memberships = membershipRepository.findByUserId(userId);

        List<TenantResponse> tenantResponses = memberships.stream()
                .map(tenantMapper::toTenantResponse)
                .toList();

        return new TenantList(tenantResponses);
    }

    /**
     * Reading a tenant needs nothing more than belonging to it — but it does need that. The lookup used to
     * accept any membership row, so a REMOVED or still-INVITED member kept read access.
     */
    @Override
    public TenantResponse getTenant(Long tenantId, TenantUserDetails userDetails) {
        Membership membership =
                tenantMembershipResolver.requireActiveMembership(userDetails.getUserId(), tenantId);

        return tenantMapper.toTenantResponse(membership);
    }

    /** Renaming a tenant is an OWNER action, and OWNER <em>of this tenant</em> — not of some other one. */
    @Override
    @Transactional
    public TenantResponse updateTenant(Long tenantId, UpdateTenantRequest request, TenantUserDetails userDetails) {
        Membership membership = tenantMembershipResolver.requireActiveMembershipWithAnyRole(
                userDetails.getUserId(), tenantId, RoleName.OWNER);

        Tenant tenant = membership.getTenant();
        if (request.name() != null) {
            tenant.setName(request.name());
        }
        tenantRepository.save(tenant);

        return tenantMapper.toTenantResponse(membership);
    }

    /**
     * Deleting a tenant had no authorization at all: it looked the tenant up by id and dropped it, so any
     * authenticated caller who got past the (wrongly scoped) role filter could destroy any tenant on the
     * platform. It requires the OWNER role in the very tenant named in the path — and that check runs
     * first, before the tenant is even looked up, so a refused caller cannot delete a single row.
     *
     * <p><strong>This is a hard delete of everything the tenant owns, and it is irreversible.</strong>
     * The alternative considered was a soft delete: mark the tenant archived and keep the rows. It was
     * rejected because a tenant that is only <em>flagged</em> deleted is still a live tenant everywhere
     * that does not know to filter it — the SDK config endpoints, API-key authentication, token refresh,
     * tenant switching and the membership resolver would each have to learn about the flag, and any one
     * of them missing it leaves a "deleted" tenant still serving flag evaluations. A half-hidden tenant is
     * worse than either honest answer. Retention, if it is wanted, belongs upstream of this endpoint as an
     * export or a scheduled purge, not as a flag this cascade would have to be rewritten around. The guard
     * against a mistaken call is therefore the authorization above plus the documented irreversibility;
     * a two-step confirmation is noted as a follow-up in API_REFERENCE.md because it would change the
     * request contract, not this method.
     *
     * <p>Order is FK-safe, deepest first, and mirrors {@code FeatureService.deleteFeature} for the feature
     * subtree — rules and overrides, then statuses, then the features and envs they name:
     *
     * <ol>
     *   <li>{@code feature_rule}, {@code feature_user_override} — grandchildren of the tenant's features</li>
     *   <li>{@code feature_env_status} — references both {@code feature} and {@code env}</li>
     *   <li>{@code feature}, {@code env}</li>
     *   <li>{@code user_invitations}, {@code api_keys} — leaves</li>
     *   <li>{@code refresh_tokens} — self-referencing, so the {@code replaced_by} chain is broken first</li>
     *   <li>{@code membership_roles}, then {@code memberships}</li>
     *   <li>the {@code tenants} row itself</li>
     * </ol>
     *
     * <p>Every step is a single bulk statement: a tenant with thousands of features is deleted in a fixed
     * number of queries and none of its rows is loaded into memory. The flip side of bulk JPQL is that it
     * neither cascades nor updates the persistence context, which is why {@code membership_roles} is
     * deleted explicitly (Hibernate would otherwise have done it on entity removal) and why every query is
     * declared {@code flushAutomatically}/{@code clearAutomatically}.
     *
     * <p>{@code REQUIRED} with no {@code noRollbackFor}: all of it commits or none of it does. A tenant
     * left half-deleted — flags gone, memberships still granting access — is worse than one that refused
     * to delete.
     *
     * <p>No domain event is emitted. {@code FeatureChangeEvent} cannot express this: its envelope requires
     * a non-null {@code featureKey} and {@code envName}, and its {@code changeType} enum has no symbol for
     * a tenant-level change, so announcing this would mean an Avro schema change and a new topic contract.
     * Fanning it out as one synthetic event per feature/env would also be a lie (no flag changed state)
     * and O(features x envs) messages for a tenant that no longer exists. {@code deleteFeature} likewise
     * publishes nothing today, and {@code docs/feature-events.md} does not define create/delete events at
     * all — a tenant-lifecycle topic is the right home for this, and is left as a follow-up.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void deleteTenant(Long tenantId, TenantUserDetails userDetails) {
        tenantMembershipResolver.requireActiveMembershipWithAnyRole(
                userDetails.getUserId(), tenantId, RoleName.OWNER);

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        I18N.getMessage(Messages.RESOURCE_TENANT_NOT_FOUND)));

        // Feature subtree: grandchildren, then the statuses they hang off, then the features and envs.
        int rules = featureRuleRepository.deleteAllByTenantId(tenantId);
        int overrides = featureUserOverrideRepository.deleteAllByTenantId(tenantId);
        int statuses = featureEnvStatusRepository.deleteAllByTenantId(tenantId);
        int features = featureRepository.deleteAllByTenantId(tenantId);
        int envs = envRepository.deleteAllByTenantId(tenantId);

        // Leaves of the tenant itself.
        int invitations = userInvitationRepository.deleteAllByTenantId(tenantId);
        int apiKeys = apiKeyRepository.deleteAllByTenantId(tenantId);

        // Sessions: break the rotation chain before the rows go, so the self-FK cannot decide the outcome.
        refreshTokenRepository.detachReplacementChainsByTenantId(tenantId);
        int refreshTokens = refreshTokenRepository.deleteAllByTenantId(tenantId);

        // Memberships, join rows first — a bulk delete will not clean those up for us.
        membershipRepository.deleteMembershipRolesByTenantId(tenantId);
        int memberships = membershipRepository.deleteAllByTenantId(tenantId);

        tenantRepository.delete(tenant);

        // Irreversible and silent otherwise: leave a trace of what the request actually destroyed.
        LOGGER.info("Tenant {} deleted by user {}: {} features, {} envs, {} statuses, {} rules, "
                        + "{} overrides, {} invitations, {} api keys, {} memberships, {} refresh tokens",
                tenantId, userDetails.getUserId(), features, envs, statuses, rules, overrides,
                invitations, apiKeys, memberships, refreshTokens);
    }
}