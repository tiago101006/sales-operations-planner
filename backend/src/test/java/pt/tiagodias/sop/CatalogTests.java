package pt.tiagodias.sop;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import pt.tiagodias.sop.identity.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MYSQL_PASSWORD=test-only", "spring.application.name=sop-catalog-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogTests {
    private static final String PASSWORD = "Password-catalogo-123";
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private JdbcTemplate jdbc;
    private Browser admin;
    private String adminId;

    @BeforeAll
    void accounts() throws Exception {
        adminId = users.createFirstAdmin("Admin", "admin@catalog.test", PASSWORD).id();
        for (Papel role : List.of(Papel.PLANEADOR, Papel.COMERCIAL, Papel.APROVADOR, Papel.CONSUMIDOR)) {
            users.create(role.name(), role.name().toLowerCase(Locale.ROOT) + "@catalog.test", PASSWORD, Set.of(role));
        }
        admin = login("admin@catalog.test");
    }

    private final class Browser {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;

        HttpResponse<String> request(String method, String path, Object body, boolean csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf) { builder.header("X-CSRF-TOKEN", token); }
            if (body == null) { builder.method(method, HttpRequest.BodyPublishers.noBody()); }
            else { builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))); }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        void csrf() throws Exception { token = json.readTree(request("GET", "/api/v1/auth/csrf", null, false).body()).get("token").asText(); }
    }

    private Browser login(String email) throws Exception {
        var browser = new Browser();
        browser.csrf();
        assertThat(browser.request("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), true).statusCode()).isEqualTo(200);
        browser.csrf();
        return browser;
    }

    private JsonNode create(String code, String nome) throws Exception {
        var response = admin.request("POST", "/api/v1/produtos", Map.of("codigo", code, "nome", nome, "unidade", "un"), true);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).isPresent();
        return json.readTree(response.body());
    }

    private String unique() { return "T-" + UUID.randomUUID(); }

    @Test
    void listsTeamOnlyForAdminWithoutAuthenticationSecrets() throws Exception {
        var response=admin.request("GET","/api/v1/utilizadores?busca=Administrador&size=1",null,false);
        assertThat(response.statusCode()).isEqualTo(200);
        var data=json.readTree(response.body());
        assertThat(data.get("totalElements").asInt()).isEqualTo(1);
        assertThat(data.get("items").get(0).get("email").asText()).isEqualTo("admin@catalog.test");
        assertThat(data.get("items").get(0).size()).isEqualTo(7);
        assertThat(response.body()).doesNotContain("\"password\":", "password_hash", "$2");
        assertThat(new Browser().request("GET","/api/v1/utilizadores",null,false).statusCode()).isEqualTo(401);
        assertThat(login("planeador@catalog.test").request("GET","/api/v1/utilizadores",null,false).statusCode()).isEqualTo(403);
    }

    @Test
    void restrictsWritesByRoleAndRequiresCsrf() throws Exception {
        assertThat(new Browser().request("GET", "/api/v1/produtos", null, false).statusCode()).isEqualTo(401);
        var product = create(unique(), "Permissoes");
        String path = "/api/v1/produtos/" + product.get("id").asText();
        for (String role : List.of("comercial", "aprovador", "consumidor")) {
            var browser = login(role + "@catalog.test");
            assertThat(browser.request("GET", path, null, false).statusCode()).isEqualTo(200);
            assertThat(browser.request("GET", "/api/v1/produtos", null, false).statusCode()).isEqualTo(200);
            assertThat(browser.request("POST", "/api/v1/produtos", Map.of("codigo", unique(), "nome", "Recusado", "unidade", "un"), true).statusCode()).isEqualTo(403);
            assertThat(browser.request("PATCH", path, Map.of("ativo", false, "versaoEsperada", 0), true).statusCode()).isEqualTo(403);
        }
        var planner = login("planeador@catalog.test");
        assertThat(planner.request("POST", "/api/v1/produtos", Map.of("codigo", unique(), "nome", "Permitido", "unidade", "kg"), true).statusCode()).isEqualTo(201);
        assertThat(admin.request("POST", "/api/v1/produtos", Map.of("codigo", unique(), "nome", "Sem CSRF", "unidade", "un"), false).statusCode()).isEqualTo(403);
        assertThat(admin.request("DELETE", path, null, true).statusCode()).isEqualTo(403);
    }

    @Test
    void validatesInputUniquenessAndMissingProduct() throws Exception {
        String code = unique();
        var product = create(code, "Produto");
        assertThat(product.get("versao").asInt()).isZero();
        assertThat(product.get("ativo").asBoolean()).isTrue();
        assertThat(admin.request("POST", "/api/v1/produtos", Map.of("codigo", code, "nome", "Duplicado", "unidade", "un"), true).statusCode()).isEqualTo(409);
        assertThat(admin.request("POST", "/api/v1/produtos", Map.of("codigo", " ", "nome", "", "unidade", ""), true).statusCode()).isEqualTo(422);
        assertThat(admin.request("POST", "/api/v1/produtos", Map.of("codigo", "A".repeat(65), "nome", "Excesso", "unidade", "un"), true).statusCode()).isEqualTo(422);
        assertThat(admin.request("GET", "/api/v1/produtos/9223372036854775807", null, false).statusCode()).isEqualTo(404);
    }

    @Test
    void paginatesFiltersAndEscapesSearchWildcards() throws Exception {
        String prefix = "PAG-" + UUID.randomUUID().toString().substring(0, 8);
        create(prefix + "-A", "Grupo A");
        create(prefix + "-B", "Desconto 10%");
        var c = create(prefix + "-C", "Grupo C");
        assertThat(admin.request("PATCH", "/api/v1/produtos/" + c.get("id").asText(), Map.of("ativo", false, "versaoEsperada", 0), true).statusCode()).isEqualTo(200);
        var page = json.readTree(admin.request("GET", "/api/v1/produtos?q=" + prefix + "&size=2", null, false).body());
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("codigo").asText()).isEqualTo(prefix + "-A");
        var second = json.readTree(admin.request("GET", "/api/v1/produtos?q=" + prefix + "&size=2&page=1", null, false).body());
        assertThat(second.get("items").size()).isEqualTo(1);
        var inactive = json.readTree(admin.request("GET", "/api/v1/produtos?q=" + prefix + "&ativo=false", null, false).body());
        assertThat(inactive.get("totalElements").asInt()).isEqualTo(1);
        var literal = json.readTree(admin.request("GET", "/api/v1/produtos?q=%25", null, false).body());
        assertThat(literal.get("totalElements").asInt()).isEqualTo(1);
        assertThat(admin.request("GET", "/api/v1/produtos?size=201", null, false).statusCode()).isEqualTo(422);
        assertThat(admin.request("GET", "/api/v1/produtos?page=-1", null, false).statusCode()).isEqualTo(422);
    }

    @Test
    void editsUnusedProductAndRejectsStaleVersion() throws Exception {
        var product = create(unique(), "Inicial");
        String path = "/api/v1/produtos/" + product.get("id").asText();
        assertThat(admin.request("PATCH", path, Map.of("nome", "Sem versao"), true).statusCode()).isEqualTo(422);
        assertThat(admin.request("PATCH", path, Map.of("nome", "  ", "versaoEsperada", 0), true).statusCode()).isEqualTo(422);
        var changed = admin.request("PATCH", path, Map.of("codigo", unique(), "nome", "Alterado", "unidade", "kg", "ativo", false, "versaoEsperada", 0), true);
        assertThat(changed.statusCode()).isEqualTo(200);
        var dto = json.readTree(changed.body());
        assertThat(dto.get("versao").asInt()).isEqualTo(1);
        assertThat(dto.get("ativo").asBoolean()).isFalse();
        assertThat(dto.get("unidade").asText()).isEqualTo("kg");
        assertThat(admin.request("PATCH", path, Map.of("nome", "Desatualizado", "versaoEsperada", 0), true).statusCode()).isEqualTo(409);
        assertThat(admin.request("PATCH", path, Map.of("ativo", true, "versaoEsperada", 1), true).statusCode()).isEqualTo(200);
    }

    @Test
    void freezesIdentityForEveryDirectUsageButAllowsNameAndDeactivation() throws Exception {
        for (String kind : List.of("HISTORICO", "PROMOCOES", "PREVISAO", "OPERACIONAL")) {
            var product = create(unique(), "Uso " + kind);
            long productId = product.get("id").asLong();
            String marker = UUID.randomUUID().toString();
            String lotType = kind.equals("PREVISAO") ? "HISTORICO" : kind;
            jdbc.update("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em) VALUES (?,'VALIDADO','teste',?,'fixture',REPEAT('a',64),'v1',?,NOW())", lotType, marker, Long.valueOf(adminId));
            Long lotId = jdbc.queryForObject("SELECT id FROM lote WHERE nome_ficheiro=?", Long.class, marker);
            switch (kind) {
                case "HISTORICO" -> jdbc.update("INSERT INTO observacao(lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (?,?,'2026-10-01','VENDAS',10,NOW())", lotId, productId);
                case "PROMOCOES" -> jdbc.update("INSERT INTO promocao(lote_id,produto_id,descricao,inicio,fim,disponivel_em) VALUES (?,?,'Campanha','2026-10-01','2026-10-31',NOW())", lotId, productId);
                case "OPERACIONAL" -> jdbc.update("INSERT INTO resultado_operacional(lote_id,produto_id,periodo,stock_fecho,limite_stock,disponivel_em) VALUES (?,?,'2026-10-01',10,20,NOW())", lotId, productId);
                case "PREVISAO" -> {
                    jdbc.update("INSERT INTO execucao_previsao(lote_historico_id,tipo_serie,corte_temporal,ultimo_periodo_treino,metodo_pedido,parametros,versao_software,versao_motor,criado_por) VALUES (?,'VENDAS','2026-10-31 23:59:59','2026-10-01','MEDIA_MOVEL','{}',?,'teste',?)", lotId, marker, Long.valueOf(adminId));
                    Long execution = jdbc.queryForObject("SELECT id FROM execucao_previsao WHERE versao_software=?", Long.class, marker);
                    jdbc.update("INSERT INTO execucao_produto(execucao_id,produto_id,parametros_aplicados,estado) VALUES (?,?,'{}','PREVISTO')", execution, productId);
                }
            }
            String path = "/api/v1/produtos/" + productId;
            assertThat(admin.request("PATCH", path, Map.of("codigo", unique(), "versaoEsperada", 0), true).statusCode()).isEqualTo(409);
            assertThat(admin.request("PATCH", path, Map.of("unidade", "kg", "versaoEsperada", 0), true).statusCode()).isEqualTo(409);
            var permitted = admin.request("PATCH", path, Map.of("nome", "Nome revisto", "ativo", false, "versaoEsperada", 0), true);
            assertThat(permitted.statusCode()).isEqualTo(200);
            assertThat(json.readTree(permitted.body()).get("codigo").asText()).isEqualTo(product.get("codigo").asText());
        }
    }

    @Test
    void concurrentUpdatesFromSameVersionProduceOneConflict() throws Exception {
        var product = create(unique(), "Concorrencia");
        String path = "/api/v1/produtos/" + product.get("id").asText();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> admin.request("PATCH", path, Map.of("nome", "Primeiro", "versaoEsperada", 0), true).statusCode());
            var second = executor.submit(() -> admin.request("PATCH", path, Map.of("nome", "Segundo", "versaoEsperada", 0), true).statusCode());
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); }
        var current = json.readTree(admin.request("GET", path, null, false).body());
        assertThat(current.get("versao").asInt()).isEqualTo(1);
    }
}
