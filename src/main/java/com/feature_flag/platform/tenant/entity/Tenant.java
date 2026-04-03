package com.feature_flag.platform.tenant.entity;

import com.feature_flag.platform.common.entity.BaseEntity;
import com.feature_flag.platform.tenant.enums.TenantPlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tenants", indexes = {
        @Index(name = "idx_tenants_name", columnList = "name")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TenantPlan plan = TenantPlan.FREE;

    @OneToMany(mappedBy = "tenant")
    @Builder.Default
    private List<Membership> memberships = new ArrayList<>();

    @OneToMany(mappedBy = "tenant")
    @Builder.Default
    private List<ApiKey> apiKeys = new ArrayList<>();
}