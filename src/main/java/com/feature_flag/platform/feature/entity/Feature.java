package com.feature_flag.platform.feature.entity;

import com.feature_flag.platform.common.entity.BaseEntity;
import com.feature_flag.platform.tenant.entity.Tenant;
import jakarta.persistence.*;
import lombok.*;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Table(
        name = "feature",
        uniqueConstraints = @UniqueConstraint(name = "uk_feature_key_tenant", columnNames = {"`key`", "tenant_id"}),
        indexes = {
                @Index(name = "idx_feature_key", columnList = "`key`"),
                @Index(name = "idx_feature_tenant_id", columnList = "tenant_id")
        }
)
public class Feature extends BaseEntity {

    @Column(name = "`key`", nullable = false, length = 50)
    private String key;

    @Column
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

}
