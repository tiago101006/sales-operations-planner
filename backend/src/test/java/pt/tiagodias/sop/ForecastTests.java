package pt.tiagodias.sop;

import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
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
        properties = {"MYSQL_PASSWORD=test-only", "spring.application.name=sop-forecast-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForecastTests {
    private static final String PASSWORD = "Password-forecast-123";
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private JdbcTemplate jdbc;
    private Browser admin;
    private String adminId;
    private Long lot;

    @BeforeAll
    void fixtures() throws Exception {
        adminId = users.createFirstAdmin("Admin", "admin@forecast.test", PASSWORD).id();
        for (Papel role : List.of(Papel.PLANEADOR, Papel.COMERCIAL, Papel.APROVADOR, Papel.CONSUMIDOR)) {
            users.create(role.name(), role.name().toLowerCase(Locale.ROOT) + "@forecast.test", PASSWORD, Set.of(role));
        }
        jdbc.update("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em,validado_em) VALUES ('HISTORICO','VALIDADO','fixture','forecast.csv','fixture',REPEAT('a',64),'v1',?,'2025-04-01','2025-04-01')", Long.valueOf(adminId));
        lot = jdbc.queryForObject("SELECT id FROM lote WHERE nome_ficheiro='forecast.csv'", Long.class);
        for (String code : List.of("MAIN", "ZERO", "GAP", "SHORT", "LATE", "FUTURE")) {
            jdbc.update("INSERT INTO produto(codigo,nome,unidade) VALUES (?,?,'un')", code, code);
            if (code.equals("FUTURE")) { observation(code, "2025-04", "VENDAS", "9999", "2025-05-01"); continue; }
            for (int month = 1; month <= 3; month++) {
                if ((code.equals("GAP") && month == 2) || (code.equals("SHORT") && month != 3)) { continue; }
                observation(code, "2025-0" + month, "VENDAS", code.equals("ZERO") ? "0" : Integer.toString(month * 10),
                        code.equals("LATE") && month == 3 ? "2025-05-01" : "2025-04-01");
            }
        }
        observation("MAIN", "2025-04", "VENDAS", "9999", "2025-05-01");
        for (int month = 1; month <= 3; month++) { observation("MAIN", "2025-0" + month, "CONSUMO", Integer.toString(month * 1000), "2025-04-01"); }
        admin = login("admin@forecast.test");
    }

    private void observation(String code, String month, String type, String quantity, String available) {
        Long product = jdbc.queryForObject("SELECT id FROM produto WHERE codigo=?", Long.class, code);
        jdbc.update("INSERT INTO observacao(lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (?,?,?,?,?,?)", lot, product, month + "-01", type, new java.math.BigDecimal(quantity), available);
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
        var browser = new Browser(); browser.csrf();
        assertThat(browser.request("POST", "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), true).statusCode()).isEqualTo(200);
        browser.csrf(); return browser;
    }
    private Map<String, Object> request() {
        var body = new HashMap<String, Object>();
        body.put("loteHistoricoId", lot.toString()); body.put("tipoSerie", "VENDAS");
        body.put("corteTemporal", "2025-04-15T00:00:00Z"); body.put("ultimoPeriodoTreino", "2025-03");
        body.put("horizonteMeses", 3); body.put("metodo", "MEDIA_MOVEL");
        body.put("parametros", Map.of("janela", 3, "multiplosPassos", "RECURSIVO")); return body;
    }
    private JsonNode execute(Map<String, Object> request) throws Exception {
        var response = admin.request("POST", "/api/v1/execucoes-previsao", request, true);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).isPresent(); return json.readTree(response.body());
    }
    private List<String> quantities(JsonNode execution, String code) throws Exception {
        var lines = json.readTree(admin.request("GET", "/api/v1/execucoes-previsao/" + execution.get("id").asText() + "/linhas", null, false).body());
        var result = new ArrayList<String>();
        for (var item : lines.get("items")) { if (item.get("produto").asText().equals(code)) { result.add(item.get("quantidadeBase").asText()); } }
        return result;
    }

    @Test
    void reproducesForecastAndPreservesVersionsAndSource() throws Exception {
        var first = execute(request()); var second = execute(request());
        assertThat(first.get("id").asText()).isNotEqualTo(second.get("id").asText());
        assertThat(first.get("estado").asText()).isEqualTo("CONCLUIDA");
        assertThat(first.get("criadoPor").asText()).isEqualTo(adminId);
        assertThat(first.get("versaoSoftware").asText()).isEqualTo("0.0.1-SNAPSHOT");
        assertThat(first.get("versaoMotor").asText()).isEqualTo("moving-average-recursive-v1");
        assertThat(first.get("produtosPrevistos").asInt()).isEqualTo(2);
        assertThat(first.get("totalLinhas").asInt()).isEqualTo(6);
        assertThat(quantities(first, "MAIN")).containsExactly("20.0000", "23.3333", "24.4444");
        assertThat(quantities(first, "ZERO")).containsExactly("0.0000", "0.0000", "0.0000");
        String path = "/api/v1/execucoes-previsao/";
        assertThat(admin.request("GET", path + first.get("id").asText() + "/linhas", null, false).body())
                .isEqualTo(admin.request("GET", path + second.get("id").asText() + "/linhas", null, false).body());
        assertThat(json.readTree(admin.request("GET", path + first.get("id").asText(), null, false).body())).isEqualTo(first);
    }

    @Test
    void diagnosesGapsShortSeriesAndUnknownFutureValuesWithoutImputation() throws Exception {
        var execution = execute(request());
        assertThat(execution.get("produtosInsuficientes").asInt()).isEqualTo(4);
        String diagnostics = admin.request("GET", "/api/v1/execucoes-previsao/" + execution.get("id").asText() + "/diagnosticos", null, false).body();
        assertThat(diagnostics).contains("JANELA_INCOMPLETA", "2025-02", "2025-03", "INSUFICIENTE", "LATE", "FUTURE");
        for (String code : List.of("GAP", "SHORT", "LATE", "FUTURE")) { assertThat(quantities(execution, code)).isEmpty(); }
    }

    @Test
    void usesOnlyRequiredWindowAndHandlesZeroForecasts() throws Exception {
        var body = request(); body.put("parametros", Map.of("janela", 1)); body.put("horizonteMeses", 2);
        var execution = execute(body);
        assertThat(execution.get("produtosPrevistos").asInt()).isEqualTo(4);
        assertThat(quantities(execution, "GAP")).containsExactly("30.0000", "30.0000");
        assertThat(quantities(execution, "MAIN")).containsExactly("30.0000", "30.0000");
    }

    @Test
    void finishesWithExplicitDiagnosticsWhenNoProductHasEnoughHistory() throws Exception {
        var body = request(); body.put("ultimoPeriodoTreino", "2025-06"); body.put("corteTemporal", "2025-07-15T00:00:00Z");
        var execution = execute(body);
        assertThat(execution.get("estado").asText()).isEqualTo("CONCLUIDA");
        assertThat(execution.get("produtosPrevistos").asInt()).isZero();
        assertThat(execution.get("produtosInsuficientes").asInt()).isEqualTo(6);
        assertThat(execution.get("totalLinhas").asInt()).isZero();
    }

    @Test
    void selectsConsumptionWithoutMixingSales() throws Exception {
        var body = request(); body.put("tipoSerie", "CONSUMO");
        var execution = execute(body);
        assertThat(execution.get("produtosPrevistos").asInt()).isEqualTo(1);
        assertThat(quantities(execution, "MAIN")).containsExactly("2000.0000", "2333.3333", "2444.4444");
    }

    @Test
    void rejectsInvalidParametersCutoffsAndLotStates() throws Exception {
        for (var change : List.of(Map.<String, Object>of("metodo", "ETS"), Map.<String, Object>of("horizonteMeses", 13),
                Map.<String, Object>of("parametros", Map.of("janela", 0)), Map.<String, Object>of("ultimoPeriodoTreino", "2025-13"),
                Map.<String, Object>of("corteTemporal", Instant.now().plusSeconds(86400).toString()))) {
            var body = request(); body.putAll(change);
            assertThat(admin.request("POST", "/api/v1/execucoes-previsao", body, true).statusCode()).isEqualTo(422);
        }
        var early = request(); early.put("corteTemporal", "2025-03-31T23:59:59Z");
        assertThat(admin.request("POST", "/api/v1/execucoes-previsao", early, true).statusCode()).isEqualTo(409);
        jdbc.update("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em) VALUES ('HISTORICO','REJEITADO','fixture','bad.csv','fixture',REPEAT('b',64),'v1',?,NOW())", Long.valueOf(adminId));
        var bad = request(); bad.put("loteHistoricoId", jdbc.queryForObject("SELECT id FROM lote WHERE nome_ficheiro='bad.csv'", Long.class));
        assertThat(admin.request("POST", "/api/v1/execucoes-previsao", bad, true).statusCode()).isEqualTo(409);
    }

    @Test
    void enforcesProfilesCsrfAndImmutableEndpoints() throws Exception {
        assertThat(new Browser().request("GET", "/api/v1/execucoes-previsao", null, false).statusCode()).isEqualTo(401);
        for (String role : List.of("comercial", "aprovador")) {
            var browser = login(role + "@forecast.test");
            assertThat(browser.request("POST", "/api/v1/execucoes-previsao", request(), true).statusCode()).isEqualTo(403);
            assertThat(browser.request("GET", "/api/v1/execucoes-previsao", null, false).statusCode()).isEqualTo(200);
        }
        assertThat(login("consumidor@forecast.test").request("GET", "/api/v1/execucoes-previsao", null, false).statusCode()).isEqualTo(403);
        assertThat(admin.request("POST", "/api/v1/execucoes-previsao", request(), false).statusCode()).isEqualTo(403);
        var planner = login("planeador@forecast.test");
        assertThat(planner.request("POST", "/api/v1/execucoes-previsao", request(), true).statusCode()).isEqualTo(201);
        var execution = execute(request());
        assertThat(admin.request("DELETE", "/api/v1/execucoes-previsao/" + execution.get("id").asText(), null, true).statusCode()).isEqualTo(403);
        assertThat(admin.request("GET", "/api/v1/execucoes-previsao/9223372036854775807", null, false).statusCode()).isEqualTo(404);
        assertThat(admin.request("GET", "/api/v1/execucoes-previsao?size=201", null, false).statusCode()).isEqualTo(422);
    }

    @Test
    void rollsBackExecutionAndAllForecastRowsOnPersistenceFailure() throws Exception {
        long executions = jdbc.queryForObject("SELECT COUNT(*) FROM execucao_previsao", Long.class);
        long rows = jdbc.queryForObject("SELECT COUNT(*) FROM linha_previsao", Long.class);
        long products = jdbc.queryForObject("SELECT COUNT(*) FROM execucao_produto", Long.class);
        long lastId = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM execucao_previsao", Long.class);
        jdbc.execute("ALTER TABLE linha_previsao ADD CONSTRAINT test_fail_forecast CHECK (execucao_id<=" + lastId + " OR quantidade_base<>23.3333)");
        try {
            assertThat(admin.request("POST", "/api/v1/execucoes-previsao", request(), true).statusCode()).isGreaterThanOrEqualTo(400);
        } finally { jdbc.execute("ALTER TABLE linha_previsao DROP CHECK test_fail_forecast"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execucao_previsao", Long.class)).isEqualTo(executions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linha_previsao", Long.class)).isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execucao_produto", Long.class)).isEqualTo(products);
    }
}
