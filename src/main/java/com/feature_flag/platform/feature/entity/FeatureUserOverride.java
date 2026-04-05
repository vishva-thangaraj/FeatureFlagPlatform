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
        name = "feature_user_override",
        uniqueConstraints = @UniqueConstraint(name = "uk_fuo_fes_user", columnNames = {"feature_env_status_id", "external_user_id"}),
        indexes = {
                @Index(name = "idx_fuo_fes_id", columnList = "feature_env_status_id"),
                @Index(name = "idx_fuo_external_user_id", columnList = "external_user_id")
        }
)
public class FeatureUserOverride extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_env_status_id", nullable = false, updatable = false)
    private FeatureEnvStatus featureEnvStatus;

    @Column(name = "external_user_id", nullable = false, updatable = false, length = 255)
    private String externalUserId;

    @Column(nullable = false)
    private boolean enabled;
}