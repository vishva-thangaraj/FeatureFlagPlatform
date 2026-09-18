package com.feature_flag.platform.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link AsyncConfig} previously did not implement {@link AsyncConfigurer}, so Spring never asked it for an
 * exception handler and used {@link SimpleAsyncUncaughtExceptionHandler} — which logs one line and drops the
 * exception, leaving no way to tell which invitation mail was lost. It also left the executor on the
 * {@link ThreadPoolTaskExecutor} default {@code AbortPolicy}, which throws on the <em>caller's</em> thread
 * and so could roll back an admin's committed invitation because the mail queue was full.
 */
class AsyncConfigTest {

    private AsyncConfig asyncConfig;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        asyncConfig = new AsyncConfig();
        executor = asyncConfig.taskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void implementsAsyncConfigurer_soSpringActuallyAsksForTheHandler() {
        assertThat(asyncConfig).isInstanceOf(AsyncConfigurer.class);
    }

    @Test
    void uncaughtExceptionHandler_isNotSpringsSilentDefault() {
        AsyncUncaughtExceptionHandler handler = asyncConfig.getAsyncUncaughtExceptionHandler();

        assertThat(handler).isNotNull();
        assertThat(handler).isNotInstanceOf(SimpleAsyncUncaughtExceptionHandler.class);
    }

    /** The async executor must be the very same bean, not a second pool with different settings. */
    @Test
    void asyncExecutor_isTheConfiguredTaskExecutorBean() {
        assertThat(asyncConfig.getAsyncExecutor()).isInstanceOf(ThreadPoolTaskExecutor.class);
    }

    // ------------------------------------------------------------------ rejection policy

    @Test
    void rejectionPolicy_isCallerRuns_notAbort() {
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .as("AbortPolicy would throw TaskRejectedException on the caller's transactional thread")
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    /**
     * The behaviour that matters: with every thread parked and the queue full — a stalled SMTP host — the
     * caller's {@code submit()} must not throw. If it did, the exception would propagate out of the
     * {@code @Transactional} service method and roll back the invitation the admin was told had succeeded.
     */
    @Test
    void whenThePoolAndQueueAreFull_submitDoesNotThrowAtTheCaller() throws Exception {
        ThreadPoolTaskExecutor small = new ThreadPoolTaskExecutor();
        small.setCorePoolSize(1);
        small.setMaxPoolSize(1);
        small.setQueueCapacity(1);
        small.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        small.initialize();

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            // Parks the single worker and fills the single queue slot.
            small.submit(() -> {
                started.countDown();
                awaitQuietly(release);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            small.submit(() -> awaitQuietly(release));

            AtomicBoolean overflowRan = new AtomicBoolean(false);
            assertThatCode(() -> small.submit(() -> overflowRan.set(true)))
                    .as("the caller must not see TaskRejectedException")
                    .doesNotThrowAnyException();

            assertThat(overflowRan)
                    .as("CallerRunsPolicy runs the overflow task inline rather than dropping it")
                    .isTrue();
        } finally {
            release.countDown();
            small.shutdown();
        }
    }

    // ------------------------------------------------------------------ the handler's output

    @Test
    void uncaughtExceptionHandler_logsTheMethodItsArgumentsAndTheCause() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(
                AsyncConfig.LoggingAsyncUncaughtExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Method method = Sample.class.getDeclaredMethod("sendInvitationEmail", String.class, String.class);
            RuntimeException cause = new IllegalStateException("smtp host unreachable");

            asyncConfig.getAsyncUncaughtExceptionHandler()
                    .handleUncaughtException(cause, method, "invitee@corp.com", "Acme");

            List<ILoggingEvent> events = appender.list;
            assertThat(events).hasSize(1);
            ILoggingEvent event = events.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);

            String rendered = event.getFormattedMessage();
            assertThat(rendered).contains("sendInvitationEmail");
            assertThat(rendered).contains(Sample.class.getName());
            assertThat(rendered)
                    .as("without the arguments an operator cannot tell which mail was lost")
                    .contains("invitee@corp.com")
                    .contains("Acme");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("smtp host unreachable");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** A null argument, or one with a broken {@code toString()}, must not break the reporting path. */
    @Test
    void uncaughtExceptionHandler_survivesNullAndBrokenArguments() throws Exception {
        Method method = Sample.class.getDeclaredMethod("sendInvitationEmail", String.class, String.class);
        Object brokenToString = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString blew up");
            }
        };

        assertThatCode(() -> asyncConfig.getAsyncUncaughtExceptionHandler()
                .handleUncaughtException(new RuntimeException("boom"), method, null, brokenToString))
                .doesNotThrowAnyException();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Only a source of a {@link Method} handle with a realistic signature. */
    static class Sample {
        @SuppressWarnings("unused")
        void sendInvitationEmail(String toEmail, String tenantName) {
        }
    }
}
