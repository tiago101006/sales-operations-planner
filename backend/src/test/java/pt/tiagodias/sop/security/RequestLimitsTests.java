package pt.tiagodias.sop.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class RequestLimitsTests {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void boundsJsonIncludingChunkedRequestsAndVendorMediaTypes() throws Exception {
        var filter = new RequestLimitsFilter();
        for (String type : new String[]{"application/json", "application/vnd.nexoplan+json"}) {
            for (boolean chunked : new boolean[]{false, true}) {
                var request = jsonRequest(type, "x".repeat(RequestLimitsFilter.MAX_JSON_BYTES + 1), chunked);
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Oversized JSON reached the controller"); });
                assertThat(response.getStatus()).isEqualTo(413);
            }
        }
        String body = "{\"nome\":\"Procura\"}";
        var response = new MockHttpServletResponse();
        filter.doFilter(jsonRequest("application/json", body, false), response, (req, res) -> {
            assertThat(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(body);
        });
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void malformedContentTypeReturnsControlledError() throws Exception {
        var response = new MockHttpServletResponse();
        new RequestLimitsFilter().doFilter(jsonRequest("invalid", "{}", false), response,
                (req, res) -> { throw new AssertionError("Invalid content type reached the controller"); });
        assertThat(response.getStatus()).isEqualTo(415);
    }

    @Test
    void rejectsConcurrentWorkForSameAccountAndReleasesSlotsAfterFailure() throws Exception {
        var filter = new RequestLimitsFilter();
        authenticate("planner");
        filter.doFilter(expensive(), new MockHttpServletResponse(), (req, res) -> {
            var duplicate = new MockHttpServletResponse();
            filter.doFilter(expensive(), duplicate, (nested, ignored) -> { throw new AssertionError("Same account admitted twice"); });
            assertThat(duplicate.getStatus()).isEqualTo(429);
        });
        assertThatThrownBy(() -> filter.doFilter(expensive(), new MockHttpServletResponse(), (req, res) -> {
            throw new IOException("Test failure");
        })).isInstanceOf(IOException.class);
        var admitted = new AtomicInteger();
        filter.doFilter(expensive(), new MockHttpServletResponse(), (req, res) -> admitted.incrementAndGet());
        assertThat(admitted.get()).isEqualTo(1);
    }

    @Test
    void sharesConcurrencyBudgetAcrossAccountsAndReleasesItAfterCompletion() throws Exception {
        var filter = new RequestLimitsFilter();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        FilterChain work = (req, res) -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        };
        try {
            var first = executor.submit(() -> runWork(filter, "first", work));
            var second = executor.submit(() -> runWork(filter, "second", work));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            authenticate("third");
            var response = new MockHttpServletResponse();
            filter.doFilter(expensive(), response, (req, res) -> { throw new AssertionError("Third operation admitted"); });
            assertThat(response.getStatus()).isEqualTo(429);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            filter.doFilter(expensive(), new MockHttpServletResponse(), (req, res) -> {});
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void expiresAuthenticatedSessionEvenWhileItRemainsActive() throws Exception {
        authenticate("planner");
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        var session = new MockHttpSession();
        session.setAttribute(CurrentAccountFilter.AUTHENTICATED_AT, System.currentTimeMillis() - java.time.Duration.ofHours(8).toMillis());
        request.setSession(session);
        var response = new MockHttpServletResponse();
        new CurrentAccountFilter(null, null).doFilter(request, response,
                (req, res) -> { throw new AssertionError("Expired session admitted"); });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static MockHttpServletRequest jsonRequest(String type, String body, boolean chunked) {
        MockHttpServletRequest request = chunked ? new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        } : new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setContentType(type);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest expensive() {
        var request = new MockHttpServletRequest("POST", "/api/v1/backtests");
        request.setServletPath("/api/v1/backtests");
        return request;
    }

    private static void authenticate(String account) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null, java.util.List.of()));
    }

    private static void runWork(RequestLimitsFilter filter, String account, FilterChain chain) {
        authenticate(account);
        try { filter.doFilter(expensive(), new MockHttpServletResponse(), chain); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
