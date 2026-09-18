package com.feature_flag.platform.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async plumbing for {@code @Async} methods.
 *
 * <p>Two things were silently wrong here before, and both had the same symptom: an operation the user was
 * told had succeeded quietly did not happen.
 *
 * <ol>
 *   <li>The class did not implement {@link AsyncConfigurer}, so Spring never asked it for an exception
 *       handler and fell back to {@code SimpleAsyncUncaughtExceptionHandler} — one log line, no arguments,
 *       no way to tell <em>which</em> invitation mail was lost. {@link LoggingAsyncUncaughtExceptionHandler}
 *       below replaces it.</li>
 *   <li>The executor used the {@link ThreadPoolTaskExecutor} default {@code AbortPolicy}. See
 *       {@link #taskExecutor()} for why that was the wrong choice here.</li>
 * </ol>
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Rejection policy: {@link ThreadPoolExecutor.CallerRunsPolicy}.
     *
     * <p>The default {@code AbortPolicy} throws {@code TaskRejectedException} from {@code submit()} — and
     * {@code submit()} runs on the <em>caller's</em> thread, inside the caller's {@code @Transactional}
     * method. So a stalled SMTP host that parks all 5 threads and fills the 100-slot queue would not merely
     * fail to send mail: the exception would propagate out of the service method and roll back the
     * invitation the administrator had just created. A mail outage would start destroying database work.
     *
     * <p>{@code CallerRunsPolicy} keeps the failure inside the async work instead of throwing across the
     * boundary. The overflow task runs on the calling thread, so the invitation is still committed; the
     * caller pays with latency, which also throttles the producer and lets the queue drain instead of
     * growing without bound. {@code DiscardPolicy} / {@code DiscardOldestPolicy} were rejected because they
     * drop the task with no trace at all — the exact invisibility being fixed here.
     *
     * <p>The trade-off is accepted knowingly: under saturation a request thread can block for the duration
     * of one send. That is bounded by the mail client's own connect/read timeouts and is strictly better
     * than rolling back committed-looking work. {@code setWaitForTasksToCompleteOnShutdown} plus an
     * await period stops in-flight mail being killed mid-send on redeploy.
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** The {@code @Async} proxies use the very same executor bean defined above, not a second pool. */
    @Override
    public Executor getAsyncExecutor() {
        return taskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new LoggingAsyncUncaughtExceptionHandler();
    }

    /**
     * Logs what actually failed. A {@code void @Async} method has no {@code Future} for anyone to inspect,
     * so this handler is the only place the failure can ever surface — it must therefore carry enough to
     * act on: the method that failed, the arguments it was called with, and the full cause.
     */
    static class LoggingAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {

        private static final Logger handlerLog =
                LoggerFactory.getLogger(LoggingAsyncUncaughtExceptionHandler.class);

        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            handlerLog.error("Uncaught exception in @Async method {}.{}({}) — the caller's request already "
                            + "returned successfully, so this failure is invisible to the client.",
                    method.getDeclaringClass().getName(),
                    method.getName(),
                    describeArguments(params),
                    ex);
        }

        private static String describeArguments(Object... params) {
            if (params == null || params.length == 0) {
                return "";
            }
            return Arrays.stream(params)
                    .map(LoggingAsyncUncaughtExceptionHandler::describeArgument)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
        }

        private static String describeArgument(Object param) {
            if (param == null) {
                return "null";
            }
            try {
                return String.valueOf(param);
            } catch (RuntimeException e) {
                // A broken toString() must not swallow the failure we are trying to report.
                return param.getClass().getName() + "@<toString failed>";
            }
        }
    }
}
