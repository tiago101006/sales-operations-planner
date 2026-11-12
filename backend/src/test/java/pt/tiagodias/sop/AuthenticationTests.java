package pt.tiagodias.sop;

import java.net.*;
import java.net.http.*;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import pt.tiagodias.sop.identity.*;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MYSQL_PASSWORD=test-only", "spring.application.name=sop-auth-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationTests {
    private static final String PASSWORD = "Password-de-teste-123";
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InvitationService invitations;

    @BeforeAll
    void accounts() {
        users.createFirstAdmin("Administrador", "admin@example.test", PASSWORD);
        users.create("Planeador", "planner@example.test", PASSWORD, Set.of(Papel.PLANEADOR));
    }

    private final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;

        HttpResponse<String> request(String method, String path, Object body, boolean csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf) { builder.header("X-CSRF-TOKEN", token); }
            if (body == null) { builder.method(method, HttpRequest.BodyPublishers.noBody()); }
            else { builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))); }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        void csrf() throws Exception {
            var response = request("GET", "/api/v1/auth/csrf", null, false);
            assertThat(response.statusCode()).isEqualTo(200);
            token = json.readTree(response.body()).get("token").asText();
        }

        HttpResponse<String> login(String email, String password) throws Exception {
            csrf();
            return request("POST", "/api/v1/auth/login", Map.of("email", email, "password", password), true);
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID"))
                    .findFirst().orElseThrow().getValue();
        }
    }

    @Test
    void requiresSessionAndCsrf() throws Exception {
        var browser = new Browser();
        assertThat(browser.request("GET", "/api/v1/auth/me", null, false).statusCode()).isEqualTo(401);
        assertThat(browser.request("POST", "/api/v1/auth/login", Map.of("email", "admin@example.test", "password", PASSWORD), false).statusCode()).isEqualTo(403);
        browser.csrf();
        browser.token = "invalid";
        assertThat(browser.request("POST", "/api/v1/auth/login", Map.of("email", "admin@example.test", "password", PASSWORD), true).statusCode()).isEqualTo(403);
    }

    @Test
    void rotatesSessionAndCsrfThenInvalidatesLogout() throws Exception {
        var browser = new Browser();
        browser.csrf();
        String oldSession = browser.sessionId();
        String oldToken = browser.token;
        var login = browser.request("POST", "/api/v1/auth/login", Map.of("email", "ADMIN@example.test", "password", PASSWORD), true);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("ADMIN").doesNotContain("\"password\":", "password_hash", "$2");
        assertThat(browser.sessionId()).isNotEqualTo(oldSession);
        var me = browser.request("GET", "/api/v1/auth/me", null, false);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("admin@example.test").doesNotContain("APROVADOR");
        var oldRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/me"))
                .header("Cookie", "JSESSIONID=" + oldSession).GET().build();
        assertThat(HttpClient.newHttpClient().send(oldRequest, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        browser.token = oldToken;
        assertThat(browser.request("POST", "/api/v1/auth/logout", null, true).statusCode()).isEqualTo(403);
        browser.csrf();
        assertThat(browser.request("POST", "/api/v1/auth/logout", null, true).statusCode()).isEqualTo(204);
        assertThat(browser.request("GET", "/api/v1/auth/me", null, false).statusCode()).isEqualTo(401);
    }

    @Test
    void onlyAdminCanCreateUsersAndResponsesExcludePasswordHash() throws Exception {
        var request = Map.of("nome", "Consumidor", "email", "consumer@example.test", "password", PASSWORD, "papeis", Set.of("CONSUMIDOR"));
        var planner = new Browser();
        assertThat(planner.login("planner@example.test", PASSWORD).statusCode()).isEqualTo(200);
        planner.csrf();
        assertThat(planner.request("POST", "/api/v1/utilizadores", request, true).statusCode()).isEqualTo(403);
        var admin = new Browser();
        assertThat(admin.login("admin@example.test", PASSWORD).statusCode()).isEqualTo(200);
        admin.csrf();
        var created = admin.request("POST", "/api/v1/utilizadores", request, true);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).doesNotContain("\"password\":", "password_hash", "$2");
        String id = json.readTree(created.body()).get("utilizador").get("id").asText();
        assertThat(admin.request("GET", "/api/v1/utilizadores/" + id, null, false).statusCode()).isEqualTo(200);
        assertThat(planner.request("GET", "/api/v1/utilizadores/" + id, null, false).statusCode()).isEqualTo(403);
        assertThat(admin.request("POST", "/api/v1/utilizadores", request, true).statusCode()).isEqualTo(409);
        var invalid = Map.of("nome", "", "email", "bad", "password", "short", "papeis", Set.of());
        assertThat(admin.request("POST", "/api/v1/utilizadores", invalid, true).statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM utilizador WHERE email='consumer@example.test'", String.class))
                .startsWith("$2").isNotEqualTo(PASSWORD);
        var employee = new Browser();
        assertThat(employee.login("consumer@example.test", PASSWORD).statusCode()).isEqualTo(401);
        employee.csrf();
        String token = json.readTree(created.body()).get("token").asText();
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", Map.of("token", token, "password", PASSWORD, "confirmacao", PASSWORD), true).statusCode()).isEqualTo(204);
        assertThat(employee.login("consumer@example.test", PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsWrongCredentialsAndDisabledAccountsIncludingExistingSession() throws Exception {
        assertThat(new Browser().login("missing@example.test", PASSWORD).statusCode()).isEqualTo(401);
        assertThat(new Browser().login("admin@example.test", "wrong-password").statusCode()).isEqualTo(401);
        users.create("A desativar", "disabled@example.test", PASSWORD, Set.of(Papel.CONSUMIDOR));
        var browser = new Browser();
        assertThat(browser.login("disabled@example.test", PASSWORD).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE utilizador SET ativo=false WHERE email='disabled@example.test'");
        assertThat(browser.request("GET", "/api/v1/auth/me", null, false).statusCode()).isEqualTo(401);
        assertThat(new Browser().login("disabled@example.test", PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    void adminDeactivationPreservesIdentityRevokesSessionsAndRequiresNewLoginAfterReactivation() throws Exception {
        var member = users.create("Colaborador", "lifecycle@example.test", PASSWORD, Set.of(Papel.PLANEADOR));
        var active = new Browser();var idle = new Browser();var admin = new Browser();
        assertThat(active.login(member.email(), PASSWORD).statusCode()).isEqualTo(200);
        assertThat(idle.login(member.email(), PASSWORD).statusCode()).isEqualTo(200);
        assertThat(admin.login("admin@example.test", PASSWORD).statusCode()).isEqualTo(200);
        admin.csrf();
        String path = "/api/v1/utilizadores/" + member.id() + "/estado";
        var disabled = admin.request("PATCH", path, Map.of("ativo", false, "versaoEsperada", 0), true);
        assertThat(disabled.statusCode()).isEqualTo(200);
        assertThat(json.readTree(disabled.body()).get("ativo").asBoolean()).isFalse();
        assertThat(json.readTree(disabled.body()).get("versao").asLong()).isEqualTo(1);
        assertThat(active.request("GET", "/api/v1/auth/me", null, false).statusCode()).isEqualTo(401);
        assertThat(new Browser().login(member.email(), PASSWORD).statusCode()).isEqualTo(401);
        assertThat(admin.request("GET", "/api/v1/utilizadores?busca=Inativo", null, false).body()).contains(member.email());
        assertThat(admin.request("PATCH", path, Map.of("ativo", true, "versaoEsperada", 0), true).statusCode()).isEqualTo(409);
        assertThat(admin.request("PATCH", path, Map.of("ativo", true, "versaoEsperada", 1), true).statusCode()).isEqualTo(200);
        // Idle during deactivation: its old session must stay invalid even after reactivation.
        assertThat(idle.request("GET", "/api/v1/auth/me", null, false).statusCode()).isEqualTo(401);
        assertThat(new Browser().login(member.email(), PASSWORD).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM utilizador WHERE id=? AND email=?", Integer.class,
                Long.valueOf(member.id()), member.email())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT ativo_novo FROM evento_estado_utilizador WHERE utilizador_id=? ORDER BY id",
                Boolean.class, Long.valueOf(member.id()))).containsExactly(false, true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evento_estado_utilizador e JOIN utilizador u ON u.id=e.alterado_por "
                + "WHERE e.utilizador_id=? AND u.email='admin@example.test'", Integer.class, Long.valueOf(member.id()))).isEqualTo(2);
        assertThat(admin.request("DELETE", "/api/v1/utilizadores/" + member.id(), null, true).statusCode()).isEqualTo(405);
    }

    @Test
    void statusChangesRequireAdminCsrfValidVersionAndCannotDisableOwnAccount() throws Exception {
        var member = users.create("Estado protegido", "protected-state@example.test", PASSWORD, Set.of(Papel.CONSUMIDOR));
        String path = "/api/v1/utilizadores/" + member.id() + "/estado";
        var body = Map.of("ativo", false, "versaoEsperada", 0);
        var planner = new Browser();
        assertThat(planner.login("planner@example.test", PASSWORD).statusCode()).isEqualTo(200);
        planner.csrf();
        assertThat(planner.request("PATCH", path, body, true).statusCode()).isEqualTo(403);
        var admin = new Browser();
        String adminId = json.readTree(admin.login("admin@example.test", PASSWORD).body()).get("id").asText();
        assertThat(admin.request("PATCH", path, body, false).statusCode()).isEqualTo(403);
        admin.csrf();
        assertThat(admin.request("PATCH", "/api/v1/utilizadores/" + adminId + "/estado", body, true).statusCode()).isEqualTo(409);
        assertThat(admin.request("PATCH", path, Map.of("ativo", false), true).statusCode()).isEqualTo(422);
        assertThat(admin.request("PATCH", "/api/v1/utilizadores/999999999/estado", body, true).statusCode()).isEqualTo(404);
        assertThat(admin.request("PATCH", path, Map.of("ativo", true, "versaoEsperada", 0), true).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evento_estado_utilizador WHERE utilizador_id=?", Integer.class,
                Long.valueOf(member.id()))).isZero();
    }

    @Test
    void invitationsArePrivateExpireAndNewLinksInvalidatePreviousOnes() throws Exception {
        var invite = invitations.create("Novo colaborador", "invite@example.test", Set.of(Papel.CONSUMIDOR), "admin@example.test");
        assertThat(invite.token()).hasSize(43);
        assertThat(invite.utilizador().passwordDefinida()).isFalse();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM convite_utilizador WHERE utilizador_id=?", String.class,
                Long.valueOf(invite.utilizador().id()))).hasSize(64).isNotEqualTo(invite.token());
        var admin = new Browser();assertThat(admin.login("admin@example.test", PASSWORD).statusCode()).isEqualTo(200);admin.csrf();
        String path = "/api/v1/utilizadores/" + invite.utilizador().id();
        assertThat(admin.request("GET", path, null, false).body()).doesNotContain(invite.token(), "token_hash");
        var replacement = admin.request("POST", path + "/convites", Map.of("versaoEsperada", invite.utilizador().versao()), true);
        assertThat(replacement.statusCode()).isEqualTo(200);
        String newToken = json.readTree(replacement.body()).get("token").asText();
        var employee = new Browser();employee.csrf();
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", Map.of("token", invite.token(), "password", PASSWORD, "confirmacao", PASSWORD), true).statusCode()).isEqualTo(400);
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", Map.of("token", newToken, "password", PASSWORD, "confirmacao", PASSWORD), false).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE convite_utilizador SET expira_em=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE utilizador_id=?", Long.valueOf(invite.utilizador().id()));
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", Map.of("token", newToken, "password", PASSWORD, "confirmacao", PASSWORD), true).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT password_definida FROM utilizador WHERE id=?", Boolean.class, Long.valueOf(invite.utilizador().id()))).isFalse();
        var planner = new Browser();planner.login("planner@example.test", PASSWORD);planner.csrf();
        assertThat(planner.request("POST", path + "/convites", Map.of("versaoEsperada", 2), true).statusCode()).isEqualTo(403);
    }

    @Test
    void disabledPendingAccountsCannotActivateAndPasswordsMustMatchUtf8Policy() throws Exception {
        var invite = invitations.create("Pendente", "pending@example.test", Set.of(Papel.PLANEADOR), "admin@example.test");
        var admin = new Browser();admin.login("admin@example.test", PASSWORD);admin.csrf();
        String path = "/api/v1/utilizadores/" + invite.utilizador().id();
        assertThat(admin.request("PATCH", path + "/estado", Map.of("ativo", false, "versaoEsperada", 1), true).statusCode()).isEqualTo(200);
        var employee = new Browser();employee.csrf();
        var body = Map.of("token", invite.token(), "password", PASSWORD, "confirmacao", PASSWORD);
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", body, true).statusCode()).isEqualTo(400);
        assertThat(admin.request("PATCH", path + "/estado", Map.of("ativo", true, "versaoEsperada", 2), true).statusCode()).isEqualTo(200);
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", body, true).statusCode()).isEqualTo(400);
        var fresh = invitations.reissue(Long.valueOf(invite.utilizador().id()), 3, "admin@example.test");
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", Map.of("token", fresh.token(), "password", PASSWORD, "confirmacao", PASSWORD + "x"), true).statusCode()).isEqualTo(422);
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", Map.of("token", fresh.token(), "password", "á".repeat(40), "confirmacao", "á".repeat(40)), true).statusCode()).isEqualTo(422);
        var valid = Map.of("token", fresh.token(), "password", PASSWORD, "confirmacao", PASSWORD);
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", valid, true).statusCode()).isEqualTo(204);
        assertThat(employee.request("POST", "/api/v1/auth/ativacao", valid, true).statusCode()).isEqualTo(400);
        assertThat(employee.request("GET", "/api/v1/auth/me", null, false).statusCode()).isEqualTo(401);
        assertThat(employee.login(fresh.utilizador().email(), PASSWORD).statusCode()).isEqualTo(200);
        assertThat(admin.request("POST", path + "/convites", Map.of("versaoEsperada", 5), true).statusCode()).isEqualTo(409);
    }

    @Test
    void onlyOneConcurrentActivationCanConsumeTheInvitation() throws Exception {
        var invite = invitations.create("Concorrente", "parallel-invite@example.test", Set.of(Papel.CONSUMIDOR), "admin@example.test");
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> activation = () -> {
                start.await();
                try { invitations.activate(invite.token(), PASSWORD, PASSWORD);return true; }
                catch (org.springframework.web.server.ResponseStatusException error) {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);return false;
                }
            };
            var first = executor.submit(activation);var second = executor.submit(activation);start.countDown();
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM convite_utilizador WHERE utilizador_id=? AND usado_em IS NOT NULL", Integer.class,
                Long.valueOf(invite.utilizador().id()))).isEqualTo(1);
    }

    @Test
    void preventsAnotherBootstrapAndRejectsOversizedUtf8Passwords() {
        assertThatThrownBy(() -> users.createFirstAdmin("Outro", "other@example.test", PASSWORD)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> users.create("Excesso", "long@example.test", "á".repeat(40), Set.of(Papel.CONSUMIDOR)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM utilizador WHERE email='other@example.test'", Integer.class)).isZero();
    }

    @Test
    void revokedRolesTakeEffectOnNextRequest() throws Exception {
        var roleUser = users.create("Revogacao", "roles@example.test", PASSWORD, Set.of(Papel.ADMIN));
        var browser = new Browser();
        assertThat(browser.login("roles@example.test", PASSWORD).statusCode()).isEqualTo(200);
        assertThat(browser.request("GET", "/api/v1/utilizadores/" + roleUser.id(), null, false).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM utilizador_papel WHERE utilizador_id=?", Long.valueOf(roleUser.id()));
        jdbc.update("INSERT INTO utilizador_papel(utilizador_id,papel) VALUES (?, 'CONSUMIDOR')", Long.valueOf(roleUser.id()));
        assertThat(browser.request("GET", "/api/v1/utilizadores/" + roleUser.id(), null, false).statusCode()).isEqualTo(403);
        assertThat(browser.request("GET", "/api/v1/auth/me", null, false).body()).contains("CONSUMIDOR").doesNotContain("ADMIN");
    }

    @Test
    void throttlesCanonicalAccountAcrossNewSessionsAndDoesNotExposeItsExistence() throws Exception {
        users.create("Limite", "rate@example.test", PASSWORD, Set.of(Papel.CONSUMIDOR));
        for (int i = 0; i < 5; i++) {
            String alias = i % 2 == 0 ? "RATE@example.test" : "ráté@example.test";
            var response = new Browser().login(alias, "wrong-password");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("Credenciais inválidas").doesNotContain("disabled", "UsernameNotFound");
        }
        var blocked = new Browser().login("rate@example.test", PASSWORD);
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
        for (int i = 0; i < 5; i++) { assertThat(new Browser().login("unknown-rate@example.test", PASSWORD).statusCode()).isEqualTo(401); }
        assertThat(new Browser().login("unknown-rate@example.test", PASSWORD).statusCode()).isEqualTo(429);
    }

    @Test
    void protectsResponseHeadersAndDeniesUnconfiguredAndPrivatePaths() throws Exception {
        var browser = new Browser();
        var response = browser.request("GET", "/", null, false);
        assertThat(response.headers().firstValue("Content-Security-Policy").orElse("")).contains("frame-ancestors 'none'", "object-src 'none'");
        assertThat(response.headers().firstValue("X-Frame-Options").orElse("")).isEqualTo("DENY");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(response.headers().firstValue("Referrer-Policy").orElse("")).isEqualTo("no-referrer");
        assertThat(response.headers().firstValue("Permissions-Policy").orElse("")).contains("camera=()", "microphone=()");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(browser.login("planner@example.test", PASSWORD).statusCode()).isEqualTo(200);
        for (String path : new String[]{"/.env", "/actuator/env", "/actuator/heapdump", "/api/v1/unconfigured", "/tmp/backups"}) {
            var denied = browser.request("GET", path, null, false);
            assertThat(denied.statusCode()).as(path).isEqualTo(403);
            assertThat(denied.body()).doesNotContain("MYSQL_PASSWORD", "$2", "java.lang");
        }
    }

    @Test
    void limitsJsonBeforeLoginAndHandlesMalformedInputWithoutEchoingSecrets() throws Exception {
        var browser = new Browser();
        browser.csrf();
        var large = browser.request("POST", "/api/v1/auth/login", Map.of("email", "admin@example.test", "password", "x".repeat(70_000)), true);
        assertThat(large.statusCode()).isEqualTo(413);
        String malformed = "{\"password\":\"secret-in-malformed-json\",";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", browser.token)
                .POST(HttpRequest.BodyPublishers.ofString(malformed)).build();
        var response = browser.client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("secret-in-malformed-json", "Exception", "java.lang");
    }
}
