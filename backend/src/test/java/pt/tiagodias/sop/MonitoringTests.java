package pt.tiagodias.sop;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pt.tiagodias.sop.identity.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"MYSQL_PASSWORD=test-only","spring.application.name=sop-monitoring-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonitoringTests {
    private static final String PASSWORD="Password-monitoring-123";
    private static final String REAL="produto;periodo;quantidade;unidade;disponivel_em\n";
    private static final String OP="produto;periodo;unidade;unidades_solicitadas;unidades_satisfeitas_no_prazo;dias_observados_com_procura;dias_com_procura_sem_stock;stock_fecho;limite_stock;unidades_perdidas;prazo_medio_dias;disponivel_em\n";
    private static final Path STORAGE=temp();
    private static Path temp() { try { return Files.createTempDirectory("sop-monitor-tests-"); }catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) { registry.add("sop.import.storage-path",STORAGE::toString); }
    private final JsonMapper json=JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private JdbcTemplate jdbc;
    private Browser admin,consumer,planner,commercial;private long product;

    @BeforeAll void fixtures()throws Exception {
        users.createFirstAdmin("Admin","admin@monitor.test",PASSWORD);
        for(Papel role:List.of(Papel.PLANEADOR,Papel.COMERCIAL,Papel.APROVADOR,Papel.CONSUMIDOR)) { users.create(role.name(),role.name().toLowerCase(Locale.ROOT)+"@monitor.test",PASSWORD,Set.of(role)); }
        jdbc.update("INSERT INTO produto(codigo,nome,unidade) VALUES ('MONITOR','Produto','un')");
        product=jdbc.queryForObject("SELECT id FROM produto WHERE codigo='MONITOR'",Long.class);
        admin=login("admin");consumer=login("consumidor");planner=login("planeador");commercial=login("comercial");
    }
    @AfterAll void cleanup()throws Exception { try(var files=Files.walk(STORAGE)) { for(var p:files.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(p); } } }
    private final class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String token;
        HttpResponse<String> request(String method,String path,Object body,boolean csrf)throws Exception {
            var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));if(csrf) { b.header("X-CSRF-TOKEN",token); }
            if(body==null) { b.method(method,HttpRequest.BodyPublishers.noBody()); }else { b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))); }
            return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode get(String path)throws Exception { return response(request("GET",path,null,false),200); }
        HttpResponse<String> post(String path,Object body)throws Exception { return request("POST",path,body,true); }
        HttpResponse<String> upload(String type,String csv,String previous,boolean csrf)throws Exception {
            String boundary="sop"+UUID.randomUUID();StringBuilder body=new StringBuilder();
            var fields=new LinkedHashMap<String,String>();fields.put("tipo",type);if(previous!=null) { fields.put("substituiLoteId",previous); }
            fields.forEach((k,v)->body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n").append(v).append("\r\n"));
            body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"ficheiro\"; filename=\"ensaio.csv\"\r\nContent-Type: text/csv\r\n\r\n").append(csv).append("\r\n--").append(boundary).append("--\r\n");
            var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/lotes")).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if(csrf) { b.header("X-CSRF-TOKEN",token); }return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf()throws Exception { token=get("/api/v1/auth/csrf").get("token").asText(); }
    }
    private Browser login(String name)throws Exception { var b=new Browser();b.csrf();response(b.post("/api/v1/auth/login",Map.of("email",name+"@monitor.test","password",PASSWORD)),200);b.csrf();return b; }
    private JsonNode response(HttpResponse<String> r,int status) { assertThat(r.statusCode()).as(r.body()).isEqualTo(status);return json.readTree(r.body()); }
    private String upload(String type,String text)throws Exception { return response(admin.upload(type,text,null,true),201).get("id").asText(); }
    private String real() { return REAL+"MONITOR;2025-02;100;un;2025-03-01T00:00:00Z\nMONITOR;2025-03;0;un;2025-04-01T00:00:00Z\n"; }
    private String operational() { return OP+"MONITOR;2025-02;un;100;80;20;2;30;10;3;2;2025-03-01T00:00:00Z\nMONITOR;2025-03;un;200;180;10;3;5;10;;4;2025-04-01T00:00:00Z\nMONITOR;2025-04;un;;;;;10;;;;2025-05-01T00:00:00Z\n"; }
    private long key(String table) { return jdbc.queryForObject("SELECT MAX(id) FROM "+table,Long.class); }
    private String plan(YearMonth first,String published) {
        // Controlled past publication dates exist only in this disposable test database.
        jdbc.update("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em,validado_em) VALUES ('HISTORICO','VALIDADO','fixture','fixture.csv','fixture',REPEAT('a',64),'v1',1,'2025-01-01','2025-01-01')");long lot=key("lote");
        jdbc.update("INSERT INTO execucao_previsao(lote_historico_id,tipo_serie,corte_temporal,ultimo_periodo_treino,horizonte_meses,metodo_pedido,parametros,versao_software,versao_motor,estado,criado_por) VALUES (?,'VENDAS',?,?,3,'MEDIA_MOVEL','{}','fixture','fixture','CONCLUIDA',1)",lot,first.minusMonths(1).atEndOfMonth(),first.minusMonths(1).atDay(1));long execution=key("execucao_previsao");
        jdbc.update("INSERT INTO execucao_produto(execucao_id,produto_id,metodo_aplicado,parametros_aplicados,estado) VALUES (?,?,'MEDIA_MOVEL','{}','PREVISTO')",execution,product);
        jdbc.update("INSERT INTO cenario(execucao_id,nome,tipo,criado_por) VALUES (?,'Fixture','PERSONALIZADO',1)",execution);long scenario=key("cenario");
        jdbc.update("INSERT INTO revisao_cenario(cenario_id,execucao_id,numero,fator,pressupostos,estado,criado_por) VALUES (?,?,1,1,'{}','APROVADA',1)",scenario,execution);long revision=key("revisao_cenario");
        jdbc.update("INSERT INTO evento_revisao(revisao_id,tipo,autor_id,ocorrido_em) VALUES (?,'APROVACAO',4,?)",revision,published);long event=key("evento_revisao");
        jdbc.update("INSERT INTO plano_publicado(revisao_id,evento_aprovacao_id,publicado_em) VALUES (?,?,?)",revision,event,published);long plan=key("plano_publicado");
        int[] bases={90,10,30},approved={110,0,20};
        for(int i=0;i<3;i++) {
            var date=first.plusMonths(i).atDay(1);jdbc.update("INSERT INTO linha_previsao VALUES (?,?,?,?)",execution,product,date,bases[i]);
            jdbc.update("INSERT INTO linha_cenario VALUES (?,?,?,?,?,?)",revision,execution,product,date,bases[i],approved[i]);
            jdbc.update("INSERT INTO linha_plano VALUES (?,?,?,?,?,?,?,?)",plan,revision,product,date,"MONITOR","un",bases[i],approved[i]);
        }
        return Long.toString(plan);
    }
    private String plan() { return plan(YearMonth.of(2025,2),"2025-01-31T23:59:59"); }
    private Map<String,Object> body(String plan,String real,String op) { var result=new HashMap<String,Object>(Map.of("planoId",plan,"loteRealizadosId",real,"periodoInicio","2025-02","periodoFim","2025-04","corteRealizados",Instant.now().toString()));if(op!=null) { result.put("loteOperacionalId",op); }return result; }
    private String evaluate(String plan,String real,String op)throws Exception { return response(admin.post("/api/v1/avaliacoes",body(plan,real,op)),201).get("id").asText(); }

    @Test void importsRealizedZerosAndInactiveProductsWithoutOverwritingHistory()throws Exception {
        jdbc.update("UPDATE produto SET ativo=false WHERE id=?",product);String lot;
        try { lot=upload("REALIZADOS","\uFEFF"+real()); }finally { jdbc.update("UPDATE produto SET ativo=true WHERE id=?",product); }
        var rows=admin.get("/api/v1/lotes/"+lot+"/observacoes");assertThat(rows.get("totalElements").asInt()).isEqualTo(2);
        assertThat(rows.get("items").get(1).get("quantidade").asText()).isEqualTo("0.0000");assertThat(rows.get("items").get(0).get("tipo").asText()).isEqualTo("PROCURA_REALIZADA");
        response(admin.request("GET","/api/v1/lotes/"+lot+"/operacionais",null,false),409);
        String stored=jdbc.queryForObject("SELECT localizacao_ficheiro FROM lote WHERE id=?",String.class,Long.valueOf(lot));assertThat(Files.readString(Path.of(stored))).isEqualTo("\uFEFF"+real());
    }
    @Test void rejectsBadOutcomesAtomicallyWithDiagnostics()throws Exception {
        for(var entry:List.of(Map.entry("REALIZADOS",real()+"MONITOR;2025-02;2;un;2025-03-01T00:00:00Z\n"),
                Map.entry("REALIZADOS",real().replace(";100;",";-1;")),Map.entry("REALIZADOS",real().replace(";un;",";kg;")),
                Map.entry("REALIZADOS",real().replace("MONITOR;","UNKNOWN;")),Map.entry("REALIZADOS",real().replace("2025-03-01T00:00:00Z","2999-03-01T00:00:00Z")),
                Map.entry("OPERACIONAL",operational().replace(";100;80;",";100;101;")),Map.entry("OPERACIONAL",operational().replace(";20;2;",";20;21;")),
                Map.entry("OPERACIONAL",operational().replace(";20;2;",";20.5;2;")),Map.entry("OPERACIONAL",operational().replace(";100;80;",";;80;")),
                Map.entry("OPERACIONAL",operational().replace(";20;2;",";32;2;")))) {
            var failed=response(admin.upload(entry.getKey(),entry.getValue(),null,true),422);String lot=failed.get("loteId").asText();
            assertThat(admin.get("/api/v1/lotes/"+lot).get("estado").asText()).isEqualTo("REJEITADO");
            for(String table:List.of("observacao","resultado_operacional")) { assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE lote_id=?",Long.class,Long.valueOf(lot))).isZero(); }
            assertThat(admin.get("/api/v1/lotes/"+lot+"/erros").get("totalElements").asInt()).isPositive();
        }
    }
    @Test void calculatesAcceptanceCaseOnCommonPairsAndSeparatesHorizons()throws Exception {
        String evaluation=evaluate(plan(),upload("REALIZADOS",real()),null);var group=consumer.get("/api/v1/avaliacoes/"+evaluation+"/precisao").get("items").get(0);var m=group.get("metricas");
        assertThat(m.get("paresMae").asInt()).isEqualTo(2);assertThat(m.get("paresMape").asInt()).isEqualTo(1);assertThat(m.get("zerosExcluidosMape").asInt()).isEqualTo(1);assertThat(m.get("emFalta").asInt()).isEqualTo(1);
        assertThat(m.get("maeBase").asText()).isEqualTo("10.0000");assertThat(m.get("maeAprovado").asText()).isEqualTo("5.0000");assertThat(m.get("mapeBase").asText()).isEqualTo("10.0000");assertThat(m.get("mapeAprovado").asText()).isEqualTo("10.0000");assertThat(m.get("ganhoAjuste").asText()).isEqualTo("5.0000");assertThat(m.get("coberturaPercentagem").asText()).isEqualTo("66.6667");
        assertThat(group.get("porHorizonte").size()).isEqualTo(3);assertThat(group.get("porHorizonte").get(1).get("metricas").get("mapeBase").isNull()).isTrue();
        var cells=consumer.get("/api/v1/avaliacoes/"+evaluation+"/celulas").get("items");assertThat(cells.get(1).get("quantidadeRealizada").asText()).isEqualTo("0.0000");assertThat(cells.get(2).get("motivoExclusao").asText()).isEqualTo("SEM_REALIZADO");
    }
    @Test void weightsOperationalRatiosAndKeepsMissingFieldsNullIndependentlyOfActuals()throws Exception {
        String op=upload("OPERACIONAL",operational());var rows=admin.get("/api/v1/lotes/"+op+"/operacionais").get("items");assertThat(rows.get(1).get("valores").get("unidades_perdidas").isNull()).isTrue();
        String evaluation=evaluate(plan(),upload("REALIZADOS",real()),op);var m=consumer.get("/api/v1/avaliacoes/"+evaluation+"/operacao").get("items").get(0);
        assertThat(m.get("nivelServicoPercentagem").asText()).isEqualTo("86.6667");assertThat(m.get("ruturaPercentagem").asText()).isEqualTo("16.6667");assertThat(m.get("excessoStockMedio").asText()).isEqualTo("10.0000");assertThat(m.get("unidadesPerdidasTotal").asText()).isEqualTo("3.0000");assertThat(m.get("paresStock").asInt()).isEqualTo(2);
        var cells=consumer.get("/api/v1/avaliacoes/"+evaluation+"/celulas").get("items");assertThat(cells.get(0).get("operacional").get("prazoMedioDias").asText()).isEqualTo("2.0000");assertThat(cells.get(2).get("operacional").get("excessoStock").isNull()).isTrue();assertThat(cells.get(2).get("elegivelPrecisao").asBoolean()).isFalse();
        String zeros=upload("OPERACIONAL",OP+"MONITOR;2025-02;un;0;0;0;0;0;0;0;0;2025-03-01T00:00:00Z\n");var z=consumer.get("/api/v1/avaliacoes/"+evaluate(plan(),upload("REALIZADOS",real()),zeros)+"/operacao").get("items").get(0);
        assertThat(z.get("nivelServicoPercentagem").isNull()).isTrue();assertThat(z.get("ruturaPercentagem").isNull()).isTrue();assertThat(z.get("excessoStockMedio").asText()).isEqualTo("0.0000");assertThat(z.get("unidadesPerdidasTotal").asText()).isEqualTo("0.0000");
    }
    @Test void excludesOutcomesKnownAfterCutWithoutBlockingIndependentOperationalData()throws Exception {
        String real=upload("REALIZADOS",real()),op=upload("OPERACIONAL",operational().replace("2025-04-01T00:00:00Z","2025-06-01T00:00:00Z"));
        for(String lot:List.of(real,op)) { jdbc.update("UPDATE lote SET disponivel_em='2025-01-01',validado_em='2025-01-01' WHERE id=?",Long.valueOf(lot)); }
        jdbc.update("UPDATE observacao SET disponivel_em='2025-06-01' WHERE lote_id=? AND periodo='2025-02-01'",Long.valueOf(real));
        var request=body(plan(),real,op);request.put("corteRealizados","2025-05-01T00:00:00Z");String id=response(admin.post("/api/v1/avaliacoes",request),201).get("id").asText();
        var cell=consumer.get("/api/v1/avaliacoes/"+id+"/celulas").get("items").get(0);assertThat(cell.get("motivoExclusao").asText()).isEqualTo("REALIZADO_APOS_CORTE");assertThat(cell.get("quantidadeRealizada").isNull()).isTrue();assertThat(cell.get("operacional").isNull()).isFalse();
        var second=consumer.get("/api/v1/avaliacoes/"+id+"/celulas").get("items").get(1);assertThat(second.get("operacional").isNull()).isTrue();assertThat(second.get("motivoOperacional").asText()).isEqualTo("OPERACIONAL_APOS_CORTE");
        var m=consumer.get("/api/v1/avaliacoes/"+id+"/precisao").get("items").get(0).get("metricas");assertThat(m.get("realizadosAposCorte").asInt()).isEqualTo(1);assertThat(m.get("paresMae").asInt()).isEqualTo(1);assertThat(m.get("mapeBase").isNull()).isTrue();
    }
    @Test void excludesLatePlansAndUnclosedFutureMonths()throws Exception {
        String real=upload("REALIZADOS",real()),late=plan(YearMonth.of(2025,2),"2025-03-15T00:00:00");String id=evaluate(late,real,null);
        var m=consumer.get("/api/v1/avaliacoes/"+id+"/precisao").get("items").get(0).get("metricas");assertThat(m.get("planosTardios").asInt()).isEqualTo(2);assertThat(m.get("maeBase").isNull()).isTrue();
        var current=YearMonth.now(ZoneOffset.UTC);var next=current.plusMonths(1);String future=plan(current,LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1).toString());var request=body(future,real,null);request.put("periodoInicio",next.toString());request.put("periodoFim",next.plusMonths(1).toString());
        id=response(admin.post("/api/v1/avaliacoes",request),201).get("id").asText();m=consumer.get("/api/v1/avaliacoes/"+id+"/precisao").get("items").get(0).get("metricas");assertThat(m.get("periodosNaoFechados").asInt()).isEqualTo(2);assertThat(m.get("paresMae").asInt()).isZero();assertThat(m.get("coberturaPercentagem").asText()).isEqualTo("0.0000");
    }
    @Test void correctionsProduceSeparateEvaluationsAndPreservePreviousResults()throws Exception {
        String plan=plan(),original=upload("REALIZADOS",real());String id=evaluate(plan,original,null);var before=consumer.get("/api/v1/avaliacoes/"+id+"/precisao");
        String corrected=response(admin.upload("REALIZADOS",real().replace(";100;",";110;"),original,true),201).get("id").asText();String next=evaluate(plan,corrected,null);
        assertThat(next).isNotEqualTo(id);assertThat(consumer.get("/api/v1/avaliacoes/"+id+"/precisao")).isEqualTo(before);
        assertThat(consumer.get("/api/v1/avaliacoes/"+next+"/precisao").get("items").get(0).get("metricas").get("maeAprovado").asText()).isEqualTo("0.0000");
        String op=upload("OPERACIONAL",operational());response(admin.upload("REALIZADOS",real(),op,true),409);
    }
    @Test void enforcesRolesCsrfImmutableEvaluationAndInputValidation()throws Exception {
        String plan=plan(),real=upload("REALIZADOS",real()),id=evaluate(plan,real,null);var request=body(plan,real,null);
        response(new Browser().request("GET","/api/v1/avaliacoes",null,false),401);response(consumer.post("/api/v1/avaliacoes",request),403);response(commercial.post("/api/v1/avaliacoes",request),403);
        response(admin.request("POST","/api/v1/avaliacoes",request,false),403);response(planner.post("/api/v1/avaliacoes",request),201);
        response(consumer.upload("REALIZADOS",real(),null,true),403);response(admin.upload("REALIZADOS",real(),null,false),403);
        for(String suffix:List.of("","/precisao","/operacao","/celulas")) { consumer.get("/api/v1/avaliacoes/"+id+suffix);response(consumer.request("GET","/api/v1/avaliacoes/9999999"+suffix,null,false),404); }
        response(admin.request("DELETE","/api/v1/avaliacoes/"+id,null,true),403);response(admin.request("PATCH","/api/v1/avaliacoes/"+id,Map.of(),true),403);
        response(consumer.request("GET","/api/v1/avaliacoes/"+id+"/precisao?size=201",null,false),422);assertThat(consumer.get("/api/v1/avaliacoes/"+id+"/celulas?size=1").get("totalPages").asInt()).isEqualTo(3);
        for(var pair:List.of(Map.entry("periodoInicio","2025-05"),Map.entry("periodoInicio","1900-01"),Map.entry("corteRealizados","2999-01-01T00:00:00Z"))) { var invalid=new HashMap<>(request);invalid.put(pair.getKey(),pair.getValue());response(admin.post("/api/v1/avaliacoes",invalid),422); }
        var wrong=new HashMap<>(request);wrong.put("loteOperacionalId",real);response(admin.post("/api/v1/avaliacoes",wrong),409);
        wrong=new HashMap<>(request);wrong.put("corteRealizados","2025-05-01T00:00:00Z");response(admin.post("/api/v1/avaliacoes",wrong),409);
        wrong=new HashMap<>(request);wrong.put("planoId","99999999");response(admin.post("/api/v1/avaliacoes",wrong),404);
    }
    @Test void rollsBackHeaderWhenCellWriteFailsAndEnforcesObservationProvenance()throws Exception {
        String plan=plan(),real=upload("REALIZADOS",real());long before=jdbc.queryForObject("SELECT COUNT(*) FROM avaliacao",Long.class);
        jdbc.execute("ALTER TABLE avaliacao_celula ADD CONSTRAINT test_monitor_rollback CHECK (plano_id<>"+Long.parseLong(plan)+")");
        try { response(admin.post("/api/v1/avaliacoes",body(plan,real,null)),500); }finally { jdbc.execute("ALTER TABLE avaliacao_celula DROP CHECK test_monitor_rollback"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM avaliacao",Long.class)).isEqualTo(before);
        String id=evaluate(plan,real,null),other=upload("REALIZADOS",real());long foreign=jdbc.queryForObject("SELECT MIN(id) FROM observacao WHERE lote_id=?",Long.class,Long.valueOf(other));
        assertThatThrownBy(()->jdbc.update("UPDATE avaliacao_celula SET observacao_id=? WHERE avaliacao_id=? AND periodo='2025-02-01'",foreign,Long.valueOf(id))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
