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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"MYSQL_PASSWORD=test-only","spring.application.name=sop-backtest-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BacktestTests {
    private final JsonMapper json=JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private JdbcTemplate jdbc;
    private Browser admin;private String adminId;private long lot;private long product;
    private static final String PASSWORD="Password-backtest-123";

    @BeforeAll
    void fixtures()throws Exception {
        adminId=users.createFirstAdmin("Admin","admin@backtest.test",PASSWORD).id();
        for(Papel role:List.of(Papel.PLANEADOR,Papel.COMERCIAL,Papel.APROVADOR,Papel.CONSUMIDOR)) {
            users.create(role.name(),role.name().toLowerCase(Locale.ROOT)+"@backtest.test",PASSWORD,Set.of(role));
        }
        lot=newLot();
        jdbc.update("INSERT INTO produto(codigo,nome,unidade) VALUES ('BACKTEST','Linear','un')");
        product=jdbc.queryForObject("SELECT id FROM produto WHERE codigo='BACKTEST'",Long.class);
        for(int month=1;month<=12;month++) {
            var period=java.time.YearMonth.of(2025,month);
            jdbc.update("INSERT INTO observacao(lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (?,?,?,'VENDAS',?,?)",lot,product,period.atDay(1),month*10,period.plusMonths(1).atDay(1).atStartOfDay());
        }
        admin=login("admin@backtest.test");
    }
    private long newLot() {
        String marker=UUID.randomUUID()+".csv";
        jdbc.update("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em,validado_em) VALUES ('HISTORICO','VALIDADO','fixture',?,'fixture',REPEAT('a',64),'v1',?,'2026-01-01','2026-01-01')",marker,Long.valueOf(adminId));
        return jdbc.queryForObject("SELECT id FROM lote WHERE nome_ficheiro=?",Long.class,marker);
    }
    private long cloneLot() {
        long copy=newLot();
        jdbc.update("INSERT INTO observacao(lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) SELECT ?,produto_id,periodo,tipo,quantidade,disponivel_em FROM observacao WHERE lote_id=?",copy,lot);
        return copy;
    }
    private final class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String token;
        HttpResponse<String> request(String method,String path,Object body,boolean csrf)throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
            if(csrf) { builder.header("X-CSRF-TOKEN",token); }
            if(body==null) { builder.method(method,HttpRequest.BodyPublishers.noBody()); }
            else { builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))); }
            return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf()throws Exception { token=json.readTree(request("GET","/api/v1/auth/csrf",null,false).body()).get("token").asText(); }
    }
    private Browser login(String email)throws Exception {
        var browser=new Browser();browser.csrf();
        assertThat(browser.request("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),true).statusCode()).isEqualTo(200);
        browser.csrf();return browser;
    }
    private Map<String,Object> body(long lot) {
        return new HashMap<>(Map.of("loteHistoricoId",Long.toString(lot),"tipoSerie","VENDAS","primeiroCorte","2025-06","ultimoCorte","2025-08",
                "horizonteMeses",3,"janelaMediaMovel",3,"historicoEtsMeses",24));
    }
    private JsonNode execute(Map<String,Object> body)throws Exception {
        var response=admin.request("POST","/api/v1/backtests",body,true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }
    private JsonNode cells(JsonNode backtest)throws Exception { return json.readTree(admin.request("GET","/api/v1/backtests/"+backtest.get("id").asText()+"/celulas",null,false).body()).get("items"); }
    private JsonNode groups(JsonNode backtest)throws Exception { return json.readTree(admin.request("GET","/api/v1/backtests/"+backtest.get("id").asText()+"/comparacao",null,false).body()).get("items"); }

    @Test
    void evaluatesOnCommonPairsByHorizonAndReproducesResults()throws Exception {
        long operational=jdbc.queryForObject("SELECT COUNT(*) FROM execucao_previsao",Long.class);
        var first=execute(body(lot));var second=execute(body(lot));
        assertThat(first.get("modo").asText()).isEqualTo("SIMULACAO_RETROSPETIVA");
        assertThat(first.get("estado").asText()).isEqualTo("CONCLUIDO");assertThat(first.get("totalCelulas").asInt()).isEqualTo(9);
        assertThat(first.get("criadoPor").asText()).isEqualTo(adminId);
        assertThat(cells(first)).isEqualTo(cells(second));assertThat(groups(first)).isEqualTo(groups(second));
        var groups=groups(first);assertThat(groups.size()).isEqualTo(3);
        var h1=groups.get(0).get("metricas");
        assertThat(h1.get("paresComparaveis").asInt()).isEqualTo(3);
        assertThat(h1.get("maeMediaMovel").asText()).isEqualTo("20.0000");assertThat(h1.get("maeEts").asText()).isEqualTo("0.0000");
        assertThat(h1.get("mapeMediaMovel").asText()).isEqualTo("25.2646");assertThat(h1.get("coberturaPercentagem").asText()).isEqualTo("100.0000");
        assertThat(groups.get(1).get("metricas").get("maeMediaMovel").asText()).isEqualTo("26.6667");
        assertThat(groups.get(2).get("metricas").get("maeMediaMovel").asText()).isEqualTo("35.5556");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execucao_previsao",Long.class)).isEqualTo(operational);
    }
    @Test
    void neverUsesFutureTargetsToFitForecastsOrChooseEtsParameters()throws Exception {
        long copy=cloneLot();jdbc.update("UPDATE observacao SET quantidade=9999 WHERE lote_id=? AND periodo='2025-09-01'",copy);
        var original=cells(execute(body(lot)));var changed=cells(execute(body(copy)));
        assertThat(original.size()).isEqualTo(changed.size());
        boolean changedActual=false;
        for(int i=0;i<original.size();i++) {
            assertThat(original.get(i).get("previsaoMediaMovel")).isEqualTo(changed.get(i).get("previsaoMediaMovel"));
            assertThat(original.get(i).get("previsaoEts")).isEqualTo(changed.get(i).get("previsaoEts"));
            assertThat(original.get(i).get("parametrosEts")).isEqualTo(changed.get(i).get("parametrosEts"));
            if(!original.get(i).get("realizado").equals(changed.get(i).get("realizado"))) { changedActual=true; }
        }
        assertThat(changedActual).isTrue();
    }
    @Test
    void respectsAvailabilityInTrainingAndActualCutoff()throws Exception {
        long copy=cloneLot();jdbc.update("UPDATE observacao SET disponivel_em='2025-08-01' WHERE lote_id=? AND periodo='2025-06-01'",copy);
        var body=body(copy);body.put("ultimoCorte","2025-07");body.put("horizonteMeses",1);
        var result=execute(body);var cells=cells(result);
        assertThat(cells.get(0).get("estadoMediaMovel").asText()).isEqualTo("INSUFICIENTE");
        assertThat(cells.get(0).get("estadoEts").asText()).isEqualTo("INSUFICIENTE");
        assertThat(cells.get(1).get("estadoEts").asText()).isEqualTo("PREVISTO");
        assertThat(groups(result).get(0).get("metricas").get("coberturaPercentagem").asText()).isEqualTo("50.0000");
        var limited=body(lot);limited.put("ultimoCorte","2025-06");limited.put("corteRealizados","2025-08-31T23:59:59Z");
        var actuals=cells(execute(limited));
        assertThat(actuals.get(0).get("realizado").asText()).isEqualTo("70.0000");
        assertThat(actuals.get(1).get("realizado").isNull()).isTrue();assertThat(actuals.get(2).get("realizado").isNull()).isTrue();
    }
    @Test
    void distinguishesZerosMissingActualsAndNoCommonForecastPopulation()throws Exception {
        long copy=cloneLot();jdbc.update("UPDATE observacao SET quantidade=0 WHERE lote_id=? AND periodo='2025-07-01'",copy);
        jdbc.update("DELETE FROM observacao WHERE lote_id=? AND periodo='2025-08-01'",copy);
        var body=body(copy);body.put("ultimoCorte","2025-06");body.put("horizonteMeses",2);
        var result=execute(body);var groups=groups(result);
        var zero=groups.get(0).get("metricas");
        assertThat(zero.get("paresComparaveis").asInt()).isEqualTo(1);assertThat(zero.get("zerosExcluidosMape").asInt()).isEqualTo(1);
        assertThat(zero.get("maeEts").asText()).isEqualTo("70.0000");assertThat(zero.get("mapeEts").isNull()).isTrue();
        assertThat(groups.get(1).get("metricas").get("realizadosEmFalta").asInt()).isEqualTo(1);
        var shortBody=body(lot);shortBody.put("primeiroCorte","2025-03");shortBody.put("ultimoCorte","2025-03");shortBody.put("horizonteMeses",1);
        var unavailable=groups(execute(shortBody)).get(0).get("metricas");
        assertThat(unavailable.get("semPrevisaoEts").asInt()).isEqualTo(1);
        assertThat(unavailable.get("maeMediaMovel").isNull()).isTrue();assertThat(unavailable.get("maeEts").isNull()).isTrue();
    }
    @Test
    void enforcesProfilesCsrfSuggestionsAndLimits()throws Exception {
        assertThat(new Browser().request("GET","/api/v1/backtests",null,false).statusCode()).isEqualTo(401);
        assertThat(admin.request("POST","/api/v1/backtests",body(lot),false).statusCode()).isEqualTo(403);
        for(String role:List.of("comercial","aprovador")) {
            var reader=login(role+"@backtest.test");assertThat(reader.request("GET","/api/v1/backtests",null,false).statusCode()).isEqualTo(200);
            assertThat(reader.request("POST","/api/v1/backtests",body(lot),true).statusCode()).isEqualTo(403);
        }
        assertThat(login("consumidor@backtest.test").request("GET","/api/v1/backtests",null,false).statusCode()).isEqualTo(403);
        assertThat(login("planeador@backtest.test").request("POST","/api/v1/backtests",body(lot),true).statusCode()).isEqualTo(201);
        var suggested=admin.request("GET","/api/v1/backtests/sugestao?loteHistoricoId="+lot,null,false);
        assertThat(suggested.statusCode()).isEqualTo(200);assertThat(json.readTree(suggested.body()).get("primeiroCorte").asText()).isEqualTo("2025-06");
        var invalid=body(lot);invalid.put("primeiroCorte","2020-01");assertThat(admin.request("POST","/api/v1/backtests",invalid,true).statusCode()).isEqualTo(422);
        invalid=body(lot);invalid.put("corteRealizados",Instant.now().plusSeconds(86400).toString());assertThat(admin.request("POST","/api/v1/backtests",invalid,true).statusCode()).isEqualTo(422);
        assertThat(admin.request("GET","/api/v1/backtests/9223372036854775807",null,false).statusCode()).isEqualTo(404);
        assertThat(admin.request("GET","/api/v1/backtests?size=201",null,false).statusCode()).isEqualTo(422);
    }
    @Test
    void keepsProductsAndUnitsSeparateAndRejectsActualsFromOtherLots()throws Exception {
        long copy=cloneLot();jdbc.update("INSERT INTO produto(codigo,nome,unidade) VALUES ('BACKTEST-KG','Peso','kg')");
        long weight=jdbc.queryForObject("SELECT id FROM produto WHERE codigo='BACKTEST-KG'",Long.class);
        jdbc.update("INSERT INTO observacao(lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) SELECT lote_id,?,periodo,tipo,quantidade/10,disponivel_em FROM observacao WHERE lote_id=? AND produto_id=?",weight,copy,product);
        var result=execute(body(copy));var groups=groups(result);assertThat(groups.size()).isEqualTo(6);
        var units=new HashSet<String>();for(var group:groups) { units.add(group.get("unidade").asText()); }
        assertThat(units).containsExactlyInAnyOrder("un","kg");
        long wrong=jdbc.queryForObject("SELECT id FROM observacao WHERE lote_id=? AND produto_id=? AND periodo='2025-07-01'",Long.class,lot,product);
        assertThatThrownBy(()->jdbc.update("UPDATE backtest_celula SET observacao_id=? WHERE backtest_id=? AND produto_id=? AND corte='2025-06-01' AND horizonte=1",wrong,result.get("id").asLong(),product))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test
    void rollsBackHeaderAndAllCellsWhenSecondHorizonCannotBeWritten()throws Exception {
        long last=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM backtest",Long.class);
        long headers=jdbc.queryForObject("SELECT COUNT(*) FROM backtest",Long.class);long cells=jdbc.queryForObject("SELECT COUNT(*) FROM backtest_celula",Long.class);
        jdbc.execute("ALTER TABLE backtest_celula ADD CONSTRAINT test_backtest_rollback CHECK (backtest_id<="+last+" OR horizonte<>2)");
        try { assertThat(admin.request("POST","/api/v1/backtests",body(lot),true).statusCode()).isGreaterThanOrEqualTo(400); }
        finally { jdbc.execute("ALTER TABLE backtest_celula DROP CHECK test_backtest_rollback"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM backtest",Long.class)).isEqualTo(headers);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM backtest_celula",Long.class)).isEqualTo(cells);
    }
}
