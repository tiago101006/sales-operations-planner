package pt.tiagodias.sop;

import java.net.*;
import java.net.http.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
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
        properties={"MYSQL_PASSWORD=test-only","spring.application.name=sop-scenario-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioTests {
    private final JsonMapper json=JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private JdbcTemplate jdbc;
    private Browser admin,planner;private String adminId,plannerId;private long execution,lot,product,zero;
    private static final String PASSWORD="Password-scenarios-123";

    @BeforeAll
    void fixtures()throws Exception {
        adminId=users.createFirstAdmin("Admin","admin@scenario.test",PASSWORD).id();
        plannerId=users.create("Planner","planeador@scenario.test",PASSWORD,Set.of(Papel.PLANEADOR)).id();
        for(Papel role:List.of(Papel.COMERCIAL,Papel.APROVADOR,Papel.CONSUMIDOR)) { users.create(role.name(),role.name().toLowerCase(Locale.ROOT)+"@scenario.test",PASSWORD,Set.of(role)); }
        jdbc.update("INSERT INTO produto(codigo,nome,unidade) VALUES ('SCENARIO','Produto','un'),('ZERO','Zero','un')");
        product=jdbc.queryForObject("SELECT id FROM produto WHERE codigo='SCENARIO'",Long.class);zero=jdbc.queryForObject("SELECT id FROM produto WHERE codigo='ZERO'",Long.class);
        jdbc.update("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em,validado_em) VALUES ('HISTORICO','VALIDADO','fixture','scenario.csv','fixture',REPEAT('a',64),'v1',?,'2026-11-01','2026-11-01')",Long.valueOf(adminId));
        lot=jdbc.queryForObject("SELECT id FROM lote WHERE nome_ficheiro='scenario.csv'",Long.class);
        execution=newExecution("100",true,"CONCLUIDA");
        admin=login("admin@scenario.test");planner=login("planeador@scenario.test");
    }
    private long newExecution(String quantity,boolean lines,String state) {
        String marker=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO execucao_previsao(lote_historico_id,tipo_serie,corte_temporal,ultimo_periodo_treino,horizonte_meses,metodo_pedido,parametros,versao_software,versao_motor,estado,criado_por) VALUES (?,'VENDAS','2026-11-05','2026-10-01',3,'MEDIA_MOVEL','{\"janela\":3}',?,'fixture',?,?)",lot,marker,state,Long.valueOf(adminId));
        long id=jdbc.queryForObject("SELECT id FROM execucao_previsao WHERE versao_software=?",Long.class,marker);
        if(lines) {
            for(long p:List.of(product,zero)) {
                jdbc.update("INSERT INTO execucao_produto(execucao_id,produto_id,metodo_aplicado,parametros_aplicados,estado) VALUES (?,?,'MEDIA_MOVEL','{}','PREVISTO')",id,p);
                for(int i=0;i<3;i++) { jdbc.update("INSERT INTO linha_previsao VALUES (?,?,?,?)",id,p,LocalDate.of(2026,11,1).plusMonths(i),p==product?new BigDecimal(quantity):BigDecimal.ZERO); }
            }
        }
        return id;
    }
    private final class Browser {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String token;
        HttpResponse<String> request(String method,String path,Object body,boolean csrf)throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));if(csrf) { builder.header("X-CSRF-TOKEN",token); }
            if(body==null) { builder.method(method,HttpRequest.BodyPublishers.noBody()); }
            else { builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))); }
            return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode get(String path)throws Exception { var response=request("GET",path,null,false);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body()); }
        void csrf()throws Exception { token=get("/api/v1/auth/csrf").get("token").asText(); }
    }
    private Browser login(String email)throws Exception {
        var browser=new Browser();browser.csrf();assertThat(browser.request("POST","/api/v1/auth/login",Map.of("email",email,"password",PASSWORD),true).statusCode()).isEqualTo(200);browser.csrf();return browser;
    }
    private Map<String,Object> createBody(long source,String type,String factor) {
        return Map.of("execucaoId",Long.toString(source),"nome",type+UUID.randomUUID(),"tipo",type,"fator",factor,"pressupostos",Map.of("descricao","Pressuposto de teste"));
    }
    private JsonNode create(String type,String factor)throws Exception {
        var response=admin.request("POST","/api/v1/cenarios",createBody(execution,type,factor),true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return json.readTree(response.body());
    }
    private String revision(JsonNode scenario) { return scenario.get("revisao").get("id").asText(); }
    private Map<String,Object> adjustment(long p,String value,long version,String reason) {
        return Map.of("produtoId",Long.toString(p),"periodo","2026-11","quantidade",value,"versaoEsperada",version,"justificacao",reason);
    }
    private JsonNode adjust(Browser browser,String revision,String value,long version)throws Exception {
        var response=browser.request("POST","/api/v1/revisoes/"+revision+"/ajustes",adjustment(product,value,version,"Campanha confirmada"),true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return json.readTree(response.body());
    }
    private JsonNode line(String revision,String code)throws Exception {
        for(var row:admin.get("/api/v1/revisoes/"+revision+"/linhas").get("items")) { if(row.get("produto").asText().equals(code) && row.get("periodo").asText().equals("2026-11")) { return row; } }
        throw new AssertionError(code);
    }
    private JsonNode copy(Browser browser,JsonNode scenario,String origin,long version,String factor)throws Exception {
        var body=new HashMap<String,Object>(Map.of("revisaoOrigemId",origin,"versaoOrigemEsperada",version));if(factor!=null) { body.put("fator",factor); }
        var response=browser.request("POST","/api/v1/cenarios/"+scenario.get("cenario").get("id").asText()+"/revisoes",body,true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return json.readTree(response.body());
    }

    @Test
    void preservesBaselineAndOtherScenarioAndAuditsSequentialOverrides()throws Exception {
        var optimistic=create("OTIMISTA","1.1");var conservative=create("CONSERVADOR","0.9");String revision=revision(optimistic);
        assertThat(line(revision,"SCENARIO").get("quantidadeCalculada").asText()).isEqualTo("110.0000");
        var first=adjust(admin,revision,"120",0);assertThat(first.get("versaoAtual").asInt()).isEqualTo(1);
        assertThat(first.get("ajuste").get("valorAnterior").asText()).isEqualTo("110.0000");
        assertThat(first.get("ajuste").get("autorId").asText()).isEqualTo(adminId);
        assertThat(first.get("ajuste").get("justificacao").asText()).isEqualTo("Campanha confirmada");
        assertThat(line(revision,"SCENARIO").get("quantidadeFinal").asText()).isEqualTo("120.0000");
        assertThat(line(revision(conservative),"SCENARIO").get("quantidadeFinal").asText()).isEqualTo("90.0000");
        assertThat(jdbc.queryForObject("SELECT quantidade_base FROM linha_previsao WHERE execucao_id=? AND produto_id=? AND periodo='2026-11-01'",BigDecimal.class,execution,product)).isEqualByComparingTo("100");
        var second=adjust(planner,revision,"125",1);assertThat(second.get("ajuste").get("valorAnterior").asText()).isEqualTo("120.0000");
        assertThat(second.get("ajuste").get("sequencia").asInt()).isEqualTo(2);assertThat(second.get("ajuste").get("autorId").asText()).isEqualTo(plannerId);
    }
    @Test
    void rejectsInvalidAdjustmentsStaleVersionsAndFrozenRevisions()throws Exception {
        String rev=revision(create("OTIMISTA","1.1"));
        for(String reason:List.of(""," \t\n ","\u00a0")) { assertThat(admin.request("POST","/api/v1/revisoes/"+rev+"/ajustes",adjustment(product,"120",0,reason),true).statusCode()).isEqualTo(422); }
        for(String amount:List.of("-1","1.00001","100000000000000")) { assertThat(admin.request("POST","/api/v1/revisoes/"+rev+"/ajustes",adjustment(product,amount,0,"Motivo"),true).statusCode()).isEqualTo(422); }
        var conflict=admin.request("POST","/api/v1/revisoes/"+rev+"/ajustes",adjustment(product,"120",9,"Motivo"),true);
        assertThat(conflict.statusCode()).isEqualTo(409);assertThat(json.readTree(conflict.body()).get("currentVersion").asInt()).isZero();
        assertThat(admin.request("POST","/api/v1/revisoes/"+rev+"/ajustes",adjustment(999999,"120",0,"Motivo"),true).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE revisao_cenario SET estado='EM_REVISAO' WHERE id=?",Long.valueOf(rev));
        assertThat(admin.request("POST","/api/v1/revisoes/"+rev+"/ajustes",adjustment(product,"120",0,"Motivo"),true).statusCode()).isEqualTo(409);
        assertThat(admin.get("/api/v1/revisoes/"+rev+"/ajustes").get("totalElements").asInt()).isZero();
    }
    @Test
    void snapshotsInheritedAuditEvenWhenOriginDraftChangesLater()throws Exception {
        var original=create("OTIMISTA","1.1");String root=revision(original);adjust(admin,root,"120",0);
        var child=copy(planner,original,root,1,null);String childId=child.get("id").asText();
        assertThat(child.get("totalAjustesLocais").asInt()).isZero();assertThat(child.get("criadoPor").asText()).isEqualTo(plannerId);
        assertThat(line(childId,"SCENARIO").get("quantidadeFinal").asText()).isEqualTo("120.0000");
        adjust(admin,root,"150",1);
        var inherited=admin.get("/api/v1/revisoes/"+childId+"/ajustes?incluirHerdados=true");
        assertThat(inherited.get("totalElements").asInt()).isEqualTo(1);
        assertThat(inherited.get("items").get(0).get("herdado").asBoolean()).isTrue();
        assertThat(inherited.get("items").get(0).get("autorId").asText()).isEqualTo(adminId);
        assertThat(inherited.get("items").get(0).get("valorNovo").asText()).isEqualTo("120.0000");
        adjust(planner,childId,"130",0);
        var grandchild=copy(admin,original,childId,1,null);String grandchildId=grandchild.get("id").asText();
        var history=admin.get("/api/v1/revisoes/"+grandchildId+"/ajustes?incluirHerdados=true");
        assertThat(history.get("totalElements").asInt()).isEqualTo(2);
        assertThat(history.toString()).contains("120.0000","130.0000").doesNotContain("150.0000");
        assertThat(line(grandchildId,"SCENARIO").get("quantidadeFinal").asText()).isEqualTo("130.0000");
    }
    @Test
    void recalculatesFromBaselineAndStopsInheritanceWhenFactorChanges()throws Exception {
        var original=create("OTIMISTA","1.1");String root=revision(original);adjust(admin,root,"125",0);
        var reset=copy(planner,original,root,1,"1.2");String resetId=reset.get("id").asText();
        assertThat(line(resetId,"SCENARIO").get("quantidadeCalculada").asText()).isEqualTo("120.0000");
        assertThat(line(resetId,"SCENARIO").get("quantidadeFinal").asText()).isEqualTo("120.0000");
        assertThat(reset.get("pressupostos").get("herdaAjustes").asBoolean()).isFalse();
        assertThat(admin.get("/api/v1/revisoes/"+resetId+"/ajustes?incluirHerdados=true").get("totalElements").asInt()).isZero();
        var next=copy(admin,original,resetId,0,null);
        assertThat(admin.get("/api/v1/revisoes/"+next.get("id").asText()+"/ajustes?incluirHerdados=true").get("totalElements").asInt()).isZero();
        assertThat(line(root,"SCENARIO").get("quantidadeFinal").asText()).isEqualTo("125.0000");
    }
    @Test
    void comparesSameExecutionAndHandlesZeroBaselinesWithoutDivisionByZero()throws Exception {
        var a=create("OTIMISTA","1.1");var b=create("CONSERVADOR","0.9");String ra=revision(a),rb=revision(b);
        var adjusted=admin.request("POST","/api/v1/revisoes/"+ra+"/ajustes",adjustment(zero,"12",0,"Procura confirmada"),true);assertThat(adjusted.statusCode()).isEqualTo(201);
        assertThat(line(ra,"ZERO").get("desvioPercentual").isNull()).isTrue();
        var comparison=admin.get("/api/v1/comparacao-cenarios?revisaoIds="+ra+","+rb+"&size=1");
        assertThat(comparison.get("produtos").get("totalElements").asInt()).isEqualTo(2);assertThat(comparison.get("produtos").get("items").size()).isEqualTo(1);
        var values=comparison.get("produtos").get("items").get(0).get("periodos").get(0).get("cenarios");
        assertThat(values.get(0).get("quantidadeFinal").asText()).isEqualTo("110.0000");assertThat(values.get(1).get("quantidadeFinal").asText()).isEqualTo("90.0000");
        long other=newExecution("200",true,"CONCLUIDA");var response=admin.request("POST","/api/v1/cenarios",createBody(other,"OTIMISTA","1.1"),true);
        String foreign=revision(json.readTree(response.body()));
        assertThat(admin.request("GET","/api/v1/comparacao-cenarios?revisaoIds="+ra+","+foreign,null,false).statusCode()).isEqualTo(409);
        assertThat(admin.request("GET","/api/v1/comparacao-cenarios?revisaoIds="+ra+","+ra,null,false).statusCode()).isEqualTo(422);
    }
    @Test
    void concurrentAdjustmentsUsingSameVersionProduceOneEventAndOneConflict()throws Exception {
        String rev=revision(create("OTIMISTA","1.1"));var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();return admin.request("POST","/api/v1/revisoes/"+rev+"/ajustes",adjustment(product,"120",0,"Admin"),true).statusCode();});
            var second=pool.submit(()->{start.await();return planner.request("POST","/api/v1/revisoes/"+rev+"/ajustes",adjustment(product,"130",0,"Planner"),true).statusCode();});
            start.countDown();assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(201,409);
        } finally { pool.shutdownNow(); }
        assertThat(admin.get("/api/v1/revisoes/"+rev).get("versao").asInt()).isEqualTo(1);
        assertThat(admin.get("/api/v1/revisoes/"+rev+"/ajustes").get("totalElements").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evento_revisao WHERE revisao_id=? AND tipo='AJUSTE'",Long.class,Long.valueOf(rev))).isEqualTo(1);
    }
    @Test
    void enforcesProfilesCsrfAndReadOnlyAuditEndpoints()throws Exception {
        var scenario=create("OTIMISTA","1.1");String rev=revision(scenario);adjust(admin,rev,"120",0);
        assertThat(new Browser().request("GET","/api/v1/cenarios",null,false).statusCode()).isEqualTo(401);
        for(String role:List.of("comercial","aprovador")) {
            var reader=login(role+"@scenario.test");assertThat(reader.request("GET","/api/v1/revisoes/"+rev+"/ajustes?incluirHerdados=true",null,false).statusCode()).isEqualTo(200);
            assertThat(reader.request("POST","/api/v1/revisoes/"+rev+"/ajustes",adjustment(product,"140",1,"Motivo"),true).statusCode()).isEqualTo(403);
        }
        assertThat(login("consumidor@scenario.test").request("GET","/api/v1/cenarios",null,false).statusCode()).isEqualTo(403);
        assertThat(admin.request("POST","/api/v1/cenarios",createBody(execution,"OTIMISTA","1.1"),false).statusCode()).isEqualTo(403);
        assertThat(planner.request("POST","/api/v1/cenarios",createBody(execution,"OTIMISTA","1.1"),true).statusCode()).isEqualTo(201);
        assertThat(admin.request("DELETE","/api/v1/revisoes/"+rev+"/ajustes/1",null,true).statusCode()).isEqualTo(403);
        assertThat(admin.request("PATCH","/api/v1/revisoes/"+rev,Map.of("estado","APROVADA"),true).statusCode()).isEqualTo(403);
        assertThat(admin.request("GET","/api/v1/revisoes/9223372036854775807",null,false).statusCode()).isEqualTo(404);
        assertThat(admin.request("GET","/api/v1/cenarios?size=201",null,false).statusCode()).isEqualTo(422);
        long event=jdbc.queryForObject("SELECT MAX(id) FROM evento_revisao WHERE revisao_id=?",Long.class,Long.valueOf(rev));
        String foreign=revision(create("CONSERVADOR","0.9"));long otherEvent=jdbc.queryForObject("SELECT MAX(id) FROM evento_revisao WHERE revisao_id=?",Long.class,Long.valueOf(foreign));
        assertThatThrownBy(()->jdbc.update("UPDATE ajuste SET evento_id=? WHERE evento_id=?",otherEvent,event)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test
    void rollsBackAuditCellAndVersionWhenFinalWriteFails()throws Exception {
        var scenario=create("OTIMISTA","1.1");String rev=revision(scenario);long id=scenario.get("cenario").get("id").asLong();
        jdbc.execute("ALTER TABLE revisao_cenario ADD CONSTRAINT test_adjust_rollback CHECK (cenario_id<>"+id+" OR versao_lock<>1)");
        try { assertThat(admin.request("POST","/api/v1/revisoes/"+rev+"/ajustes",adjustment(product,"120",0,"Falha simulada"),true).statusCode()).isGreaterThanOrEqualTo(400); }
        finally { jdbc.execute("ALTER TABLE revisao_cenario DROP CHECK test_adjust_rollback"); }
        assertThat(line(rev,"SCENARIO").get("quantidadeFinal").asText()).isEqualTo("110.0000");
        assertThat(admin.get("/api/v1/revisoes/"+rev).get("versao").asInt()).isZero();
        assertThat(admin.get("/api/v1/revisoes/"+rev+"/ajustes").get("totalElements").asInt()).isZero();
        assertThat(admin.get("/api/v1/revisoes/"+rev+"/eventos").get("totalElements").asInt()).isEqualTo(1);
    }
    @Test
    void validatesFactorsEligibilityRoundingAndOverflowAtomically()throws Exception {
        for(var pair:List.of(List.of("BASE","1.1"),List.of("OTIMISTA","0.9"),List.of("CONSERVADOR","1.2"),List.of("PERSONALIZADO","-1"))) {
            assertThat(admin.request("POST","/api/v1/cenarios",createBody(execution,pair.get(0),pair.get(1)),true).statusCode()).isEqualTo(422);
        }
        for(long source:List.of(newExecution("100",false,"CONCLUIDA"),newExecution("100",true,"EM_EXECUCAO"))) {
            assertThat(admin.request("POST","/api/v1/cenarios",createBody(source,"OTIMISTA","1.1"),true).statusCode()).isEqualTo(409);
        }
        long large=newExecution("99999999999999.9999",true,"CONCLUIDA");long count=jdbc.queryForObject("SELECT COUNT(*) FROM cenario",Long.class);
        assertThat(admin.request("POST","/api/v1/cenarios",createBody(large,"OTIMISTA","1.1"),true).statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cenario",Long.class)).isEqualTo(count);
        long tiny=newExecution("0.0001",true,"CONCLUIDA");var response=admin.request("POST","/api/v1/cenarios",createBody(tiny,"OTIMISTA","1.5"),true);
        assertThat(response.statusCode()).isEqualTo(201);assertThat(line(revision(json.readTree(response.body())),"SCENARIO").get("quantidadeCalculada").asText()).isEqualTo("0.0002");
    }
    @Test
    void serializesNewRevisionNumbersAndRejectsWrongOriginOrVersion()throws Exception {
        var scenario=create("OTIMISTA","1.1");String origin=revision(scenario);String path="/api/v1/cenarios/"+scenario.get("cenario").get("id").asText()+"/revisoes";
        var body=Map.of("revisaoOrigemId",origin,"versaoOrigemEsperada",0);var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->admin.request("POST",path,body,true));var second=pool.submit(()->planner.request("POST",path,body,true));
            var a=first.get();var b=second.get();assertThat(a.statusCode()).isEqualTo(201);assertThat(b.statusCode()).isEqualTo(201);
            assertThat(List.of(json.readTree(a.body()).get("numero").asInt(),json.readTree(b.body()).get("numero").asInt())).containsExactlyInAnyOrder(2,3);
        } finally { pool.shutdownNow(); }
        assertThat(admin.get("/api/v1/cenarios/"+scenario.get("cenario").get("id").asText()).get("numeroRevisoes").asInt()).isEqualTo(3);
        assertThat(admin.request("POST",path,Map.of("revisaoOrigemId",origin,"versaoOrigemEsperada",9),true).statusCode()).isEqualTo(409);
        String wrong=revision(create("OTIMISTA","1.1"));assertThat(admin.request("POST",path,Map.of("revisaoOrigemId",wrong,"versaoOrigemEsperada",0),true).statusCode()).isEqualTo(409);
    }
}
