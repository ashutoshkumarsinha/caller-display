package com.example.sip.resilience;

import com.example.sip.config.GatewayConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

import java.time.Duration;

/**
 * Holds independent HSS / APNS / FCM circuit breakers and a shared push rate limiter.
 */
public final class GatewayResilience {

    private final CircuitBreaker hssBreaker;
    private final CircuitBreaker apnsBreaker;
    private final CircuitBreaker fcmBreaker;
    private final RateLimiter pushRateLimiter;
    private final int pushMaxRetries;
    private final Duration pushRetryBaseDelay;

    public GatewayResilience(
            CircuitBreaker hssBreaker,
            CircuitBreaker apnsBreaker,
            CircuitBreaker fcmBreaker,
            RateLimiter pushRateLimiter,
            int pushMaxRetries,
            Duration pushRetryBaseDelay) {
        this.hssBreaker = hssBreaker;
        this.apnsBreaker = apnsBreaker;
        this.fcmBreaker = fcmBreaker;
        this.pushRateLimiter = pushRateLimiter;
        this.pushMaxRetries = pushMaxRetries;
        this.pushRetryBaseDelay = pushRetryBaseDelay;
    }

    public static GatewayResilience fromConfig(GatewayConfig config) {
        CircuitBreakerConfig hssConfig = timeBasedBreaker(
                config.hssFailureRateThreshold(),
                config.hssSlidingWindow());
        CircuitBreakerConfig apnsConfig = timeBasedBreaker(
                config.apnsFailureRateThreshold(),
                Duration.ofSeconds(10));
        CircuitBreakerConfig fcmConfig = timeBasedBreaker(
                config.fcmFailureRateThreshold(),
                Duration.ofSeconds(10));

        RateLimiterConfig rateConfig = RateLimiterConfig.custom()
                .limitForPeriod(Math.max(1, config.pushRateLimitPerSecond()))
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        return new GatewayResilience(
                CircuitBreaker.of("hss", hssConfig),
                CircuitBreaker.of("apns", apnsConfig),
                CircuitBreaker.of("fcm", fcmConfig),
                RateLimiter.of("push", rateConfig),
                2,
                Duration.ofMillis(50));
    }

    /**
     * Aggressive count-based breakers for unit tests (trip after a handful of failures).
     */
    public static GatewayResilience forTests(int rateLimitPerSecond) {
        CircuitBreakerConfig cb = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();

        RateLimiterConfig rateConfig = RateLimiterConfig.custom()
                .limitForPeriod(Math.max(1, rateLimitPerSecond))
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        return new GatewayResilience(
                CircuitBreaker.of("hss-test", CircuitBreakerConfig.from(cb).build()),
                CircuitBreaker.of("apns-test", CircuitBreakerConfig.from(cb).build()),
                CircuitBreaker.of("fcm-test", CircuitBreakerConfig.from(cb).build()),
                RateLimiter.of("push-test", rateConfig),
                2,
                Duration.ofMillis(10));
    }

    public static GatewayResilience forTests() {
        return forTests(1000);
    }

    private static CircuitBreakerConfig timeBasedBreaker(float failureRateThreshold, Duration window) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(Math.max(1, (int) window.getSeconds()))
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
    }

    public CircuitBreaker hssBreaker() {
        return hssBreaker;
    }

    public CircuitBreaker apnsBreaker() {
        return apnsBreaker;
    }

    public CircuitBreaker fcmBreaker() {
        return fcmBreaker;
    }

    public RateLimiter pushRateLimiter() {
        return pushRateLimiter;
    }

    public int pushMaxRetries() {
        return pushMaxRetries;
    }

    public Duration pushRetryBaseDelay() {
        return pushRetryBaseDelay;
    }
}
