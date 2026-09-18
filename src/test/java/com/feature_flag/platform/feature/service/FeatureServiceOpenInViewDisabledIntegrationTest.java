package com.feature_flag.platform.feature.service;

import com.feature_flag.platform.feature.dto.FeatureEnvStatusResponse;
import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.entity.Feature;
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code spring.jpa.open-in-view} is unset in this codebase, so it defaults to on and the persistence
 * context stays open for the whole request. Several read methods on {@link FeatureService} relied on
 * that without saying so: they carried no transaction of their own, and their mappers walk a LAZY
 * association ({@code status.getEnv().getName()}). Turning the property off — a routine thing to do,
 * and Spring logs a warning encouraging it — would have turned those endpoints into 500s.
 *
 * <p>This class runs with the property off and <em>without</em> a test-managed transaction, which is
 * the only combination that reproduces it: a {@code @Transactional} test would keep a persistence
 * context open and hide the problem exactly as open-in-view does.
 */
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@ActiveProfiles("test")
class FeatureServiceOpenInViewDisabledIntegrationTest {

    @Autowired private FeatureService featureService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private EnvRepository envRepository;
    @Autowired private FeatureRepository featureRepository;
    @Autowired private FeatureEnvStatusRepository featureEnvStatusRepository;

    private Tenant tenant;
    private Env env;
    private Feature feature;
    private FeatureEnvStatus status;

    @BeforeEach
    void setUp() {
        tenant = tenantRepository.save(Tenant.builder().name("OSIV Tenant").build());
        env = envRepository.save(Env.builder().name("osiv-prod").tenant(tenant).build());
        feature = featureRepository.save(Feature.builder().key("osiv-flag").tenant(tenant).build());
        status = featureEnvStatusRepository.save(FeatureEnvStatus.builder()
                .feature(feature).env(env).enabled(true).rolloutPercentage(100).build());
    }

    @AfterEach
    void tearDown() {
        // Nothing rolls back here, so clean up rather than leaving rows for the next context.
        featureEnvStatusRepository.deleteById(status.getId());
        featureRepository.deleteById(feature.getId());
        envRepository.deleteById(env.getId());
        tenantRepository.deleteById(tenant.getId());
    }

    @Test
    void listEnvStatuses_readsTheEnvironmentNameWithoutOpenInView() {
        List<FeatureEnvStatusResponse> statuses =
                featureService.listEnvStatuses(feature.getId(), tenant.getId());

        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).envName()).isEqualTo("osiv-prod");
    }

    @Test
    void theOtherReadsSurviveToo() {
        assertThatCode(() -> {
            featureService.getFeature(feature.getId(), tenant.getId());
            featureService.getFeaturesList(tenant.getId());
            featureService.listRules(feature.getId(), env.getId(), tenant.getId());
            featureService.listOverrides(feature.getId(), env.getId(), tenant.getId());
            featureService.listAttributes(feature.getId(), tenant.getId());
        }).doesNotThrowAnyException();
    }
}
