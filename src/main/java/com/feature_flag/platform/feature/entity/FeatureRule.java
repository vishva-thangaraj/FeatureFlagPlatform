package com.feature_flag.platform.feature.entity;

import com.feature_flag.platform.common.entity.BaseEntity;
import com.feature_flag.platform.feature.enums.RuleOperator;
import jakarta.persistence.*;
import lombok.*;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@Table(
        name = "feature_rule",
        indexes = {
                @Index(name = "idx_frule_fes_id", columnList = "feature_env_status_id")
        }
)
public class FeatureRule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_env_status_id", nullable = false, updatable = false)
    private FeatureEnvStatus featureEnvStatus;

    @Column(nullable = false, length = 100)
    private String attribute;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleOperator operator;

    @Column(name = "value_json", nullable = false, columnDefinition = "TEXT")
    private String valueJson;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private int priority = 0;
}