package com.feature_flag.platform.feature.entity;

import com.feature_flag.platform.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Table(
        name = "feature_env_status",
        uniqueConstraints = @UniqueConstraint(name = "uk_fes_feature_env", columnNames = {"feature_id", "env_id"}),
        indexes = {
                @Index(name = "idx_fes_feature_id", columnList = "feature_id"),
                @Index(name = "idx_fes_env_id", columnList = "env_id")
        }
)
public class FeatureEnvStatus extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_id", nullable = false, updatable = false)
    private Feature feature;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "env_id", nullable = false, updatable = false)
    private Env env;

    @Column(nullable = false)
    private boolean enabled;
}