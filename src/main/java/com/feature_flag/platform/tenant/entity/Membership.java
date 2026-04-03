package com.feature_flag.platform.tenant.entity;

import com.feature_flag.platform.auth.entity.Role;
import com.feature_flag.platform.auth.entity.User;
import com.feature_flag.platform.common.entity.BaseEntity;
import com.feature_flag.platform.tenant.enums.MembershipStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "memberships",
        uniqueConstraints = @UniqueConstraint(name = "uk_memberships_user_tenant", columnNames = {"user_id", "tenant_id"}),
        indexes = {
                @Index(name = "idx_memberships_user_id", columnList = "user_id"),
                @Index(name = "idx_memberships_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_memberships_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Membership extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MembershipStatus status = MembershipStatus.ACTIVE;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "membership_roles",
            joinColumns = @JoinColumn(name = "membership_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_membership_roles", columnNames = {"membership_id", "role_id"})
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();
}