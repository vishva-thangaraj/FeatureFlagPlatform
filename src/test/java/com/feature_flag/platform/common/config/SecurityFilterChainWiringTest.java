package com.feature_flag.platform.common.config;

import com.feature_flag.platform.common.security.JwtAuthenticationFilter;
import com.feature_flag.platform.common.security.filter.FilterChainExceptionHandler;
import com.feature_flag.platform.common.security.filter.ParamValidationFilter;
import com.feature_flag.platform.common.security.filter.RateLimitFilter;
import com.feature_flag.platform.common.security.filter.RoleAuthorizationFilter;
import com.feature_flag.platform.common.security.filter.TenantContextFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two undeclared assumptions the security chain used to rest on.
 *
 * <p><b>1. Every security filter is registered on the servlet container as well as in the security
 * chain.</b> All of them are {@code @Component}s implementing {@code Filter}, and Spring Boot
 * auto-registers every {@code Filter} bean it finds. Nothing broke only because the security chain's proxy
 * sits at order {@code -100} while a stray auto-registration gets {@code LOWEST_PRECEDENCE}, so the
 * in-chain copy always ran first and {@code OncePerRequestFilter}'s {@code ".FILTERED"} attribute turned
 * the stray into a no-op. Nothing expressed that, and {@code TenantContextFilter} even carried a defensive
 * branch for the opposite belief. If the ordering ever inverted, {@code RoleAuthorizationFilter} would run
 * with an empty {@code SecurityContext} and answer 403 to every request to every endpoint declaring a
 * role — a total outage.
 *
 * <p><b>2. The order inside the chain.</b> Several filters were added with the same computed order
 * ({@code UsernamePasswordAuthenticationFilter - 1}) and came out in insertion order, so the sequence that
 * decides whether rate limiting sees a principal, and whether role checks see the target tenant, was
 * settled by a stable sort nobody had written down.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityFilterChainWiringTest {

    /** Every filter the security chain owns, in the order the chain must run them. */
    private static final List<Class<? extends Filter>> CHAIN_ORDER = List.of(
            FilterChainExceptionHandler.class,
            JwtAuthenticationFilter.class,
            RateLimitFilter.class,
            TenantContextFilter.class,
            RoleAuthorizationFilter.class,
            ParamValidationFilter.class);

    @Autowired private ApplicationContext applicationContext;
    @Autowired private SecurityFilterChain securityFilterChain;

    // ---------------------------------------------------------------- no double registration

    @Test
    void everySecurityFilterHasItsContainerRegistrationDisabled() {
        Collection<FilterRegistrationBean> registrations =
                applicationContext.getBeansOfType(FilterRegistrationBean.class).values();

        for (Class<? extends Filter> filterType : CHAIN_ORDER) {
            Filter filter = applicationContext.getBean(filterType);

            List<FilterRegistrationBean> forThisFilter = registrations.stream()
                    .filter(registration -> registration.getFilter() == filter)
                    .toList();

            assertThat(forThisFilter)
                    .as("%s must have exactly one FilterRegistrationBean declaring what the container does"
                            + " with it; without one Spring Boot auto-registers it a second time",
                            filterType.getSimpleName())
                    .hasSize(1);
            assertThat(forThisFilter.get(0).isEnabled())
                    .as("%s must NOT be registered on the servlet container: the security chain is the only"
                            + " thing that may run it", filterType.getSimpleName())
                    .isFalse();
        }
    }

    /**
     * The same guarantee stated from the other side: no {@code Filter} bean of ours may reach the
     * container without an explicit, disabled registration.
     */
    @Test
    void noSecurityFilterBeanIsLeftForSpringBootToAutoRegister() {
        Map<String, Filter> ourFilters = applicationContext.getBeansOfType(Filter.class);
        Collection<FilterRegistrationBean> registrations =
                applicationContext.getBeansOfType(FilterRegistrationBean.class).values();

        List<String> unregistered = ourFilters.entrySet().stream()
                .filter(entry -> entry.getValue().getClass().getPackageName()
                        .startsWith("com.feature_flag.platform"))
                .filter(entry -> registrations.stream()
                        .noneMatch(registration -> registration.getFilter() == entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        assertThat(unregistered)
                .as("these Filter beans would be auto-registered on the servlet container in addition to"
                        + " the security chain; give each a FilterRegistrationBean with setEnabled(false)")
                .isEmpty();
    }

    // ---------------------------------------------------------------- the order is explicit

    @Test
    void theChainRunsTheFiltersInTheDeclaredOrder() {
        List<String> actual = securityFilterChain.getFilters().stream()
                .map(filter -> filter.getClass().getSimpleName())
                .toList();

        List<Integer> positions = CHAIN_ORDER.stream()
                .map(type -> actual.indexOf(type.getSimpleName()))
                .toList();

        assertThat(positions)
                .as("every filter must actually be in the chain; chain is %s", actual)
                .doesNotContain(-1);
        assertThat(positions)
                .as("rate limiting must come after authentication (so it can key on the user) and before"
                        + " tenant resolution (so a throttled request costs no database work); role checks"
                        + " must come after tenant resolution. Chain is %s", actual)
                .isSorted();
    }

    /**
     * The error-shape net has to WRAP Spring Security's own translator, not pre-empt it: authentication
     * and access-denied exceptions must still be handled by {@code ExceptionTranslationFilter}, and only
     * what nothing else claimed should reach the generic 500 writer.
     */
    @Test
    void theExceptionHandlerWrapsSpringSecurityOwnExceptionTranslation() {
        List<String> actual = securityFilterChain.getFilters().stream()
                .map(filter -> filter.getClass().getSimpleName())
                .toList();

        assertThat(actual.indexOf(FilterChainExceptionHandler.class.getSimpleName()))
                .as("chain is %s", actual)
                .isGreaterThanOrEqualTo(0)
                .isLessThan(actual.indexOf(ExceptionTranslationFilter.class.getSimpleName()));
    }

    /**
     * It is the outermost link that can catch anything: every filter with logic of its own runs inside it,
     * so nothing they throw can escape to the container's own error page. Only
     * {@code DisableEncodeUrlFilter} — which does nothing but wrap the response to suppress URL-rewritten
     * session ids — is allowed to precede it.
     */
    @Test
    void nothingThatCanThrowRunsOutsideTheExceptionHandler() {
        List<String> actual = securityFilterChain.getFilters().stream()
                .map(filter -> filter.getClass().getSimpleName())
                .toList();

        int handlerAt = actual.indexOf(FilterChainExceptionHandler.class.getSimpleName());
        assertThat(handlerAt).as("chain is %s", actual).isGreaterThanOrEqualTo(0);
        assertThat(actual.subList(0, handlerAt))
                .as("a filter placed outside the handler can still produce a container error page;"
                        + " chain is %s", actual)
                .containsExactly("DisableEncodeUrlFilter");
    }
}
