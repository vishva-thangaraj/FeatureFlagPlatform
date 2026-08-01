package com.feature_flag.platform.feature.entity;

import com.feature_flag.platform.common.entity.BaseEntity;
import com.feature_flag.platform.feature.enums.RuleOperator;
import com.feature_flag.platform.feature.enums.RuleValueType;
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

    /** Declared type of the value(s); required for cross-SDK comparison consistency (D4). */
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    @Builder.Default
    private RuleValueType valueType = RuleValueType.STRING;

    /** Always a JSON array (D5). Single-value operators use element [0]. */
    @Column(name = "value_json", nullable = false, columnDefinition = "TEXT")
    private String valueJson;

    /** Case-insensitive by default so country/region/locale comparisons don't silently fail (D6). */
    @Column(name = "case_sensitive", nullable = false)
    @Builder.Default
    private boolean caseSensitive = false;

    /** Inverts the match result, keeping NOT_* variants off the operator enum (D7). */
    @Column(name = "negated", nullable = false)
    @Builder.Default
    private boolean negated = false;

    /** Disabled rules are parked, not deleted, and are omitted from the client payload (D8). */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private int priority = 0;
}