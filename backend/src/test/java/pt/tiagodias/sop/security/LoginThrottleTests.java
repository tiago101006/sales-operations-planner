package pt.tiagodias.sop.security;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class LoginThrottleTests {
    @Test
    void accountLimitExpiresAndSuccessfulLoginResetsOnlyItsAccount() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(clock);
        for (int i = 0; i < 5; i++) { assertThat(throttle.reserveAccount("a@example.test")).isZero(); }
        assertThat(throttle.reserveAccount("a@example.test")).isEqualTo(900);
        assertThat(throttle.reserveAccount("b@example.test")).isZero();
        throttle.succeeded("a@example.test");
        assertThat(throttle.reserveAccount("a@example.test")).isZero();
        for (int i = 0; i < 4; i++) { throttle.reserveAccount("b@example.test"); }
        clock.now = clock.now.plusSeconds(899);
        assertThat(throttle.reserveAccount("b@example.test")).isEqualTo(1);
        clock.now = clock.now.plusSeconds(1);
        assertThat(throttle.reserveAccount("b@example.test")).isZero();
    }

    @Test
    void parallelAttemptsCannotExceedAccountLimit() throws Exception {
        var throttle = new LoginThrottle();
        var admitted = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(8);
        try {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) {
                jobs.add(executor.submit(() -> { if (throttle.reserveAccount("parallel@example.test") == 0) { admitted.incrementAndGet(); } }));
            }
            for (var job : jobs) { job.get(5, TimeUnit.SECONDS); }
        } finally { executor.shutdownNow(); }
        assertThat(admitted.get()).isEqualTo(5);
    }

    @Test
    void ipLimitCannotBeResetWithSessionsOrForwardedHeaders() throws Exception {
        var filter = new LoginRequestFilter(new LoginThrottle());
        for (int i = 0; i < 61; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setServletPath("/api/v1/auth/login");
            request.setRemoteAddr("192.0.2.1");
            request.addHeader("X-Forwarded-For", "198.51.100." + i);
            request.addHeader("Forwarded", "for=198.51.100." + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> res.getWriter().write("admitted"));
            if (i < 60) { assertThat(response.getContentAsString()).isEqualTo("admitted"); }
            else {
                assertThat(response.getStatus()).isEqualTo(429);
                assertThat(response.getHeader("Retry-After")).isNotBlank();
            }
        }
    }

    @Test
    void keyStorageIsBoundedWithoutEvictingActiveLimits() {
        var throttle = new LoginThrottle();
        for (int i = 0; i < 10_000; i++) { assertThat(throttle.reserveAccount("account" + i)).isZero(); }
        assertThat(throttle.reserveAccount("another")).isPositive();
        for (int i = 0; i < 4; i++) { assertThat(throttle.reserveAccount("account0")).isZero(); }
        assertThat(throttle.reserveAccount("account0")).isPositive();
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
