package com.feature_flag.platform.feature.service;

import com.feature_flag.platform.feature.entity.Env;
import com.feature_flag.platform.feature.entity.Feature;
import com.feature_flag.platform.feature.entity.FeatureEnvStatus;
import com.feature_flag.platform.feature.repository.EnvRepository;
import com.feature_flag.platform.feature.repository.FeatureEnvStatusRepository;
import com.feature_flag.platform.feature.repository.FeatureRepository;
import com.feature_flag.platform.tenant.entity.Tenant;
import com.feature_flag.platform.tenant.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code getAllFlagConfigs} is the endpoint every SDK in every process polls, and it claimed in a
 * comment to run a fixed number of queries. It did not: the finder it used was derived, so
 * {@code feature} and {@code env} stayed LAZY and the response builder — which puts the feature key
 * and the env name in every row — woke a proxy per flag.
 *
 * <p>This pins the cost to a constant. The count is asserted loosely: the point is that it does not
 * grow with the number of flags, not that it is exactly four.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Transactional
class FlagConfigQueryCountIntegrationTest {

    private static final int FLAG_COUNT = 25;

    @Autowired private FlagConfigService flagConfigService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private EnvRepository envRepository;
    @Autowired private FeatureRepository featureRepository;
    @Autowired private FeatureEnvStatusRepository featureEnvStatusRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @PersistenceContext private EntityManager em;

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = tenantRepository.save(Tenant.builder().name("Query Count Tenant").build());
        Env env = envRepository.save(Env.builder().name("prod").tenant(tenant).build());
        for (int i = 0; i < FLAG_COUNT; i++) {
            Feature feature = featureRepository.save(Feature.builder()
                    .key("flag-" + i).tenant(tenant).build());
            featureEnvStatusRepository.save(FeatureEnvStatus.builder()
                    .feature(feature).env(env).enabled(true).rolloutPercentage(100).build());
        }
        // Everything above is still in the persistence context, where no association is ever lazy.
        // Clearing it puts the service in the position a real request is in.
        em.flush();
        em.clear();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void bulkConfigRunsAFixedNumberOfQueries() {
        Statistics statistics = statistics();
        statistics.clear();

        var response = flagConfigService.getAllFlagConfigs("prod", tenant.getId());

        assertThat(response.flags()).hasSize(FLAG_COUNT);
        assertThat(response.flags().get(0).featureKey()).startsWith("flag-");
        assertThat(response.flags().get(0).environment()).isEqualTo("prod");

        long queries = statistics.getPrepareStatementCount();
        assertThat(queries)
                .as("one query per flag means %d flags cost %d round trips", FLAG_COUNT, queries)
                .isLessThanOrEqualTo(6);
    }

    @Test
    void singleFlagConfigRunsThreeQueries() {
        Statistics statistics = statistics();
        statistics.clear();

        var response = flagConfigService.getFlagConfig("flag-0", "prod", tenant.getId());

        assertThat(response.featureKey()).isEqualTo("flag-0");
        assertThat(response.environment()).isEqualTo("prod");
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }
}
