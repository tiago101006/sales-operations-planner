package pt.tiagodias.sop;

import java.net.*;
import java.net.http.*;
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
        properties = {"MYSQL_PASSWORD=test-only", "spring.application.name=sop-ets-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtsForecastTests {
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private JdbcTemplate jdbc;
    private HttpClient client;
    private String token;
    private Long lot;

    @BeforeAll
    void fixtures() throws Exception {
        var admin = users.createFirstAdmin("Admin", "admin@ets.test", "Password-ets-test-123");
        jdbc.update("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em,validado_em) VALUES ('HISTORICO','VALIDADO','fixture','ets.csv','fixture',REPEAT('a',64),'v1',?,'2025-09-01','2025-09-01')", Long.valueOf(admin.id()));
        lot = jdbc.queryForObject("SELECT id FROM lote WHERE nome_ficheiro='ets.csv'", Long.class);
        var data = new LinkedHashMap<String, List<Long>>();
        data.put("LONG", List.of(10L,12L,13L,12L,15L,18L,19L,21L));
        data.put("ZERO", Collections.nCopies(8,0L));
        data.put("SHORT", List.of(10L,20L,30L));
        data.put("GAP", List.of(10L,20L,30L,40L,50L,60L,70L,80L));
        data.put("LATE", List.of(10L,20L,30L,40L,50L,60L,70L,80L));
        data.put("DECLINE", List.of(100L,80L,60L,40L,20L,0L));
        data.put("OVERFLOW", List.of(90000000000000L,91000000000000L,92000000000000L,93000000000000L,94000000000000L,95000000000000L));
        for (var entry : data.entrySet()) {
            jdbc.update("INSERT INTO produto(codigo,nome,unidade) VALUES (?,?,'un')", entry.getKey(), entry.getKey());
            Long product = jdbc.queryForObject("SELECT id FROM produto WHERE codigo=?", Long.class, entry.getKey());
            for (int i = 0; i < entry.getValue().size(); i++) {
                int month = 9 - entry.getValue().size() + i;
                if (entry.getKey().equals("GAP") && month == 4) { continue; }
                String available = entry.getKey().equals("LATE") && month == 8 ? "2025-10-01" : "2025-09-01";
                jdbc.update("INSERT INTO observacao(lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (?,?,?,'VENDAS',?,?)", lot, product, "2025-0" + month + "-01", entry.getValue().get(i), available);
            }
        }
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        csrf();
        assertThat(request("POST", "/api/v1/auth/login", Map.of("email", "admin@ets.test", "password", "Password-ets-test-123")).statusCode()).isEqualTo(200);
        csrf();
    }
    private void csrf() throws Exception { token = json.readTree(request("GET", "/api/v1/auth/csrf", null).body()).get("token").asText(); }
    private HttpResponse<String> request(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (method.equals("POST")) { builder.header("X-CSRF-TOKEN", token); }
        if (body != null) { builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))); }
        else { builder.method(method, HttpRequest.BodyPublishers.noBody()); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private Map<String, Object> body(Map<String, Object> params) {
        return new HashMap<>(Map.of("loteHistoricoId", lot.toString(), "tipoSerie", "VENDAS", "metodo", "ETS", "ultimoPeriodoTreino", "2025-08",
                "corteTemporal", "2025-09-15T00:00:00Z", "horizonteMeses", 3, "parametros", params));
    }
    private JsonNode execute(Map<String, Object> body) throws Exception {
        var result = request("POST", "/api/v1/execucoes-previsao", body);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        return json.readTree(result.body());
    }
    private JsonNode diagnostic(JsonNode execution, String product) throws Exception {
        var items = json.readTree(request("GET", "/api/v1/execucoes-previsao/" + execution.get("id").asText() + "/diagnosticos", null).body()).get("items");
        for (var item : items) { if (item.get("produto").asText().equals(product)) { return item; } }
        throw new AssertionError(product);
    }

    @Test
    void fitsEtsAndPreservesLearnedParametersAndReproducibility() throws Exception {
        var first = execute(body(Map.of())); var second = execute(body(Map.of()));
        assertThat(first.get("metodo").asText()).isEqualTo("ETS");
        assertThat(first.get("produtosPrevistos").asInt()).isEqualTo(4);
        assertThat(first.get("produtosInsuficientes").asInt()).isEqualTo(3);
        assertThat(first.get("produtosRecurso").asInt()).isZero();
        assertThat(first.get("parametros").get("variante").asText()).isEqualTo("AAN");
        assertThat(first.get("parametros").has("janela")).isFalse();
        var actual = diagnostic(first, "LONG");
        assertThat(actual.get("metodoAplicado").asText()).isEqualTo("ETS(A,A,N)");
        var p = actual.get("parametrosAplicados");
        assertThat(p.get("numeroObservacoes").asInt()).isEqualTo(8);
        assertThat(p.get("alpha").asDouble()).isBetween(.1,.9);
        assertThat(p.get("beta").asDouble()).isLessThan(p.get("alpha").asDouble()).isGreaterThan(0);
        assertThat(p.get("inicioTreino").asText()).isEqualTo("2025-01");
        assertThat(p.get("sseTreino").asDouble()).isGreaterThanOrEqualTo(0);
        String firstLines = request("GET", "/api/v1/execucoes-previsao/" + first.get("id").asText() + "/linhas", null).body();
        assertThat(firstLines).isEqualTo(request("GET", "/api/v1/execucoes-previsao/" + second.get("id").asText() + "/linhas", null).body());
        assertThat(diagnostic(second,"LONG").get("parametrosAplicados")).isEqualTo(p);
    }

    @Test
    void usesMovingAverageOnlyWhenExplicitlyRequestedAndEligible() throws Exception {
        var execution = execute(body(Map.of("recurso","MEDIA_MOVEL","janelaRecurso",3)));
        assertThat(execution.get("produtosPrevistos").asInt()).isEqualTo(6);
        assertThat(execution.get("produtosRecurso").asInt()).isEqualTo(2);
        assertThat(execution.get("produtosInsuficientes").asInt()).isEqualTo(1);
        for (String code : List.of("SHORT","GAP")) {
            var diagnostic = diagnostic(execution,code);
            assertThat(diagnostic.get("estado").asText()).isEqualTo("RECURSO");
            assertThat(diagnostic.get("metodoAplicado").asText()).isEqualTo("MEDIA_MOVEL");
            assertThat(diagnostic.get("parametrosAplicados").get("janela").asInt()).isEqualTo(3);
            assertThat(diagnostic.get("parametrosAplicados").has("alpha")).isFalse();
            assertThat(diagnostic.get("diagnostico").asText()).startsWith("ETS_");
        }
        assertThat(diagnostic(execution,"LATE").get("estado").asText()).isEqualTo("INSUFICIENTE");
        assertThat(diagnostic(execution,"LATE").get("diagnostico").asText()).contains("2025-08", "RECURSO_INSUFICIENTE");
    }

    @Test
    void respectsMaximumLookbackAndReportsGapsWithoutImputation() throws Exception {
        var execution = execute(body(Map.of("historicoMeses",6)));
        var p = diagnostic(execution,"LONG").get("parametrosAplicados");
        assertThat(p.get("numeroObservacoes").asInt()).isEqualTo(6);
        assertThat(p.get("inicioTreino").asText()).isEqualTo("2025-03");
        assertThat(diagnostic(execution,"GAP").get("estado").asText()).isEqualTo("INSUFICIENTE");
        assertThat(diagnostic(execution,"GAP").get("diagnostico").asText()).contains("2025-04");
    }

    @Test
    void reportsNegativeClippingAndRejectsOverflowPerProduct() throws Exception {
        var body = body(Map.of()); body.put("horizonteMeses",12);
        var execution = execute(body);
        assertThat(execution.get("produtosErro").asInt()).isEqualTo(1);
        var decline = diagnostic(execution,"DECLINE");
        assertThat(decline.get("estado").asText()).isEqualTo("PREVISTO");
        assertThat(decline.get("parametrosAplicados").get("previsoesTruncadasEmZero").asInt()).isEqualTo(12);
        assertThat(decline.get("diagnostico").asText()).contains("TRUNCADAS_EM_ZERO");
        var overflow = diagnostic(execution,"OVERFLOW");
        assertThat(overflow.get("estado").asText()).isEqualTo("ERRO");
        assertThat(overflow.get("diagnostico").asText()).contains("FORA_LIMITE");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM linha_previsao l JOIN produto p ON p.id=l.produto_id WHERE l.execucao_id=? AND p.codigo='OVERFLOW'", Long.class, execution.get("id").asLong());
        assertThat(count).isZero();
    }

    @Test
    void rejectsUnsupportedOrAmbiguousParameters() throws Exception {
        for (var params : List.of(Map.<String,Object>of("variante","AAA"), Map.<String,Object>of("historicoMeses",5),
                Map.<String,Object>of("janela",3), Map.<String,Object>of("recurso","OUTRO"), Map.<String,Object>of("janelaRecurso",3))) {
            assertThat(request("POST", "/api/v1/execucoes-previsao", body(params)).statusCode()).isEqualTo(422);
        }
        var mm = body(Map.of("variante","AAN","janela",3)); mm.put("metodo","MEDIA_MOVEL");
        assertThat(request("POST", "/api/v1/execucoes-previsao", mm).statusCode()).isEqualTo(422);
    }

    @Test
    void keepsHistoricalMovingAverageConfigurationReadable() throws Exception {
        var mm = body(Map.of("janela",3)); mm.put("metodo","MEDIA_MOVEL");
        var execution = execute(mm);
        assertThat(execution.get("parametros").get("janela").asInt()).isEqualTo(3);
        assertThat(execution.get("parametros").get("multiplosPassos").asText()).isEqualTo("RECURSIVO");
        assertThat(execution.get("parametros").has("variante")).isFalse();
        assertThat(diagnostic(execution,"SHORT").get("metodoAplicado").asText()).isEqualTo("MEDIA_MOVEL");
    }

    @Test
    void rollsBackEtsExecutionWhenSecondForecastMonthCannotBeWritten() throws Exception {
        long last = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM execucao_previsao", Long.class);
        long executions = jdbc.queryForObject("SELECT COUNT(*) FROM execucao_previsao", Long.class);
        long lines = jdbc.queryForObject("SELECT COUNT(*) FROM linha_previsao", Long.class);
        long products = jdbc.queryForObject("SELECT COUNT(*) FROM execucao_produto", Long.class);
        jdbc.execute("ALTER TABLE linha_previsao ADD CONSTRAINT test_ets_rollback CHECK (execucao_id<=" + last + " OR periodo<>'2025-10-01')");
        try { assertThat(request("POST", "/api/v1/execucoes-previsao", body(Map.of())).statusCode()).isGreaterThanOrEqualTo(400); }
        finally { jdbc.execute("ALTER TABLE linha_previsao DROP CHECK test_ets_rollback"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execucao_previsao",Long.class)).isEqualTo(executions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linha_previsao",Long.class)).isEqualTo(lines);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execucao_produto",Long.class)).isEqualTo(products);
    }
}
