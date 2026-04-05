package com.feature_flag.platform.feature.entity;

import com.feature_flag.platform.common.entity.BaseEntity;
import com.feature_flag.platform.tenant.entity.Tenant;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(name = "env",
        uniqueConstraints = @UniqueConstraint(name = "uk_envs_name_tenant", columnNames = {"name", "tenant_id"}),
        indexes = {
                @Index(name = "idx_envs_name", columnList = "name"),
                @Index(name = "idx_envs_tenant_id", columnList = "tenant_id")
        }
)
public class Env extends BaseEntity {

    @Column(name = "name", nullable = false)
     private String name;

    @Column(name = "description")
     private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

}
