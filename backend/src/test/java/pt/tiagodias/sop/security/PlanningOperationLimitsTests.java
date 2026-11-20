package pt.tiagodias.sop.security;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class PlanningOperationLimitsTests {
    private static final List<String> POSTS = List.of(
            "/api/v1/cenarios", "/api/v1/cenarios/42/revisoes",
            "/api/v1/revisoes/42/aprovacao", "/api/v1/utilizadores", "/api/v1/avaliacoes/42/analise-inteligente");

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void planningAndAccountCreationShareTheAccountBudgetWithForecasting() throws Exception {
        var filter = new RequestLimitsFilter();
        authenticate("planner");
        filter.doFilter(request("POST", "/api/v1/execucoes-previsao"), new MockHttpServletResponse(), (req, res) -> {
            for (String path : POSTS) {
                var response = new MockHttpServletResponse();
                filter.doFilter(request("POST", path), response,
                        (nested, ignored) -> { throw new AssertionError("Parallel operation admitted: " + path); });
                assertThat(response.getStatus()).as(path).isEqualTo(429);
                assertThat(response.getHeader("Retry-After")).isEqualTo("5");
            }
            // Reads and individual adjustments remain available while a calculation runs.
            for (String[] route : List.of(new String[]{"GET", "/api/v1/cenarios"},
                    new String[]{"POST", "/api/v1/revisoes/42/ajustes"})) {
                var response = new MockHttpServletResponse();
                filter.doFilter(request(route[0], route[1]), response, (nested, ignored) -> {});
                assertThat(response.getStatus()).isEqualTo(200);
            }
        });
    }

    @Test
    void failuresInEveryNewOperationReleaseTheBudget() throws Exception {
        var filter = new RequestLimitsFilter();
        authenticate("planner");
        for (String path : POSTS) {
            assertThatThrownBy(() -> filter.doFilter(request("POST", path), new MockHttpServletResponse(),
                    (req, res) -> { throw new IOException("Simulated operation failure"); }))
                    .isInstanceOf(IOException.class);
            var response = new MockHttpServletResponse();
            filter.doFilter(request("POST", path), response, (req, res) -> {});
            assertThat(response.getStatus()).as(path).isEqualTo(200);
        }
    }

    @Test
    void publicationAndScenarioCopyUseTheGlobalBudget() throws Exception {
        var filter = new RequestLimitsFilter();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var publication = executor.submit(() -> hold(filter, "approver", "/api/v1/revisoes/42/aprovacao", entered, release));
            var copy = executor.submit(() -> hold(filter, "planner", "/api/v1/cenarios/42/revisoes", entered, release));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            authenticate("administrator");
            var response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/v1/utilizadores"), response,
                    (req, res) -> { throw new AssertionError("Third expensive operation admitted"); });
            assertThat(response.getStatus()).isEqualTo(429);
            release.countDown();
            publication.get(5, TimeUnit.SECONDS);
            copy.get(5, TimeUnit.SECONDS);
            var admitted = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/v1/cenarios"), admitted, (req, res) -> {});
            assertThat(admitted.getStatus()).isEqualTo(200);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void hold(RequestLimitsFilter filter, String account, String path,
                             CountDownLatch entered, CountDownLatch release) {
        authenticate(account);
        try {
            filter.doFilter(request("POST", path), new MockHttpServletResponse(), (req, res) -> {
                entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            });
        } catch (Exception error) {
            throw new RuntimeException(error);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    private static void authenticate(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
