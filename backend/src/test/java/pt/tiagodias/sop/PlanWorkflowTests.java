package pt.tiagodias.sop;

import java.io.StringReader;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.csv.*;
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
        properties={"MYSQL_PASSWORD=test-only","spring.application.name=sop-plan-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanWorkflowTests {
    private static final String PASSWORD="Password-planning-123";
    private static final String UNIT="kg;\"açúcar\"\nmedido";
    private final JsonMapper json=JsonMapper.builder().build();
    @LocalServerPort private int port;
    @Autowired private IdentityService users;
    @Autowired private JdbcTemplate jdbc;
    private Browser admin,planner,approver,other,dual,consumer,commercial;
    private long product,zero,lot,execution;
    private String approverId,dualId;

    @BeforeAll
    void fixtures()throws Exception {
        String adminId=users.createFirstAdmin("Admin","admin@plan.test",PASSWORD).id();
        users.create("Planeador","planner@plan.test",PASSWORD,Set.of(Papel.PLANEADOR));
        approverId=users.create("Aprovador","approver@plan.test",PASSWORD,Set.of(Papel.APROVADOR)).id();
        users.create("Outro aprovador","other@plan.test",PASSWORD,Set.of(Papel.APROVADOR));
        dualId=users.create("Planeador e aprovador","dual@plan.test",PASSWORD,Set.of(Papel.PLANEADOR,Papel.APROVADOR)).id();
        users.create("Consumidor","consumer@plan.test",PASSWORD,Set.of(Papel.CONSUMIDOR));
        users.create("Comercial","commercial@plan.test",PASSWORD,Set.of(Papel.COMERCIAL));
        jdbc.update("INSERT INTO produto(codigo,nome,unidade) VALUES ('PLAN_A','Produto','un'),('PLAN_Z','Zero',?)",UNIT);
        product=jdbc.queryForObject("SELECT id FROM produto WHERE codigo='PLAN_A'",Long.class);
        zero=jdbc.queryForObject("SELECT id FROM produto WHERE codigo='PLAN_Z'",Long.class);
        jdbc.update("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,importado_por,disponivel_em,validado_em) VALUES ('HISTORICO','VALIDADO','fixture','planning.csv','fixture',REPEAT('a',64),'v1',?,'2026-11-01','2026-11-01')",Long.valueOf(adminId));
        lot=jdbc.queryForObject("SELECT id FROM lote WHERE nome_ficheiro='planning.csv'",Long.class);
        execution=newExecution(Long.parseLong(adminId),0);
        admin=login("admin");planner=login("planner");approver=login("approver");other=login("other");
        dual=login("dual");consumer=login("consumer");commercial=login("commercial");
    }
    private long newExecution(long author,int extras) {
        String marker=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO execucao_previsao(lote_historico_id,tipo_serie,corte_temporal,ultimo_periodo_treino,horizonte_meses,metodo_pedido,parametros,versao_software,versao_motor,estado,criado_por) VALUES (?,'VENDAS','2026-11-05','2026-10-01',3,'MEDIA_MOVEL','{}',?,'fixture','CONCLUIDA',?)",lot,marker,author);
        long id=jdbc.queryForObject("SELECT id FROM execucao_previsao WHERE versao_software=?",Long.class,marker);
        var products=new ArrayList<Long>(List.of(product,zero));
        for(int i=0;i<extras;i++) {
            String code="BULK_"+marker.substring(0,8)+"_"+String.format("%03d",i);
            jdbc.update("INSERT INTO produto(codigo,nome,unidade) VALUES (?,'Teste','un')",code);
            products.add(jdbc.queryForObject("SELECT id FROM produto WHERE codigo=?",Long.class,code));
        }
        for(long p:products) {
            jdbc.update("INSERT INTO execucao_produto(execucao_id,produto_id,metodo_aplicado,parametros_aplicados,estado) VALUES (?,?,'MEDIA_MOVEL','{}','PREVISTO')",id,p);
            for(int i=0;i<3;i++) { jdbc.update("INSERT INTO linha_previsao VALUES (?,?,?,?)",id,p,LocalDate.of(2026,11,1).plusMonths(i),p==zero?BigDecimal.ZERO:new BigDecimal("100")); }
        }
        return id;
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
        JsonNode get(String path)throws Exception { return response(request("GET",path,null,false),200); }
        HttpResponse<String> post(String path,Object body)throws Exception { return request("POST",path,body,true); }
        void csrf()throws Exception { token=get("/api/v1/auth/csrf").get("token").asText(); }
    }
    private Browser login(String name)throws Exception {
        var browser=new Browser();browser.csrf();response(browser.post("/api/v1/auth/login",Map.of("email",name+"@plan.test","password",PASSWORD)),200);browser.csrf();return browser;
    }
    private JsonNode response(HttpResponse<String> response,int code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(code);return json.readTree(response.body());
    }
    private JsonNode create(Browser actor,long source)throws Exception {
        return response(actor.post("/api/v1/cenarios",Map.of("execucaoId",Long.toString(source),"nome","Ensaio "+UUID.randomUUID(),"tipo","OTIMISTA","fator","1.1","pressupostos",Map.of("descricao","Hipotese de teste"))),201);
    }
    private JsonNode create()throws Exception { return create(admin,execution); }
    private String rev(JsonNode scenario) { return scenario.get("revisao").get("id").asText(); }
    private String path(String revision,String operation) { return "/api/v1/revisoes/"+revision+"/"+operation; }
    private Map<String,Object> version(long version) { return Map.of("versaoEsperada",version); }
    private Map<String,Object> adjustment(long version) { return Map.of("produtoId",Long.toString(product),"periodo","2026-11","quantidade","120.0000","justificacao","Ajuste de ensaio","versaoEsperada",version); }
    private JsonNode submit(String revision,long version)throws Exception { return response(admin.post(path(revision,"submissao"),version(version)),200); }
    private JsonNode approve(String revision,long version)throws Exception { return response(approver.post(path(revision,"aprovacao"),version(version)),201); }
    private JsonNode revision(String revision)throws Exception { return admin.get("/api/v1/revisoes/"+revision); }
    private JsonNode copy(Browser actor,JsonNode scenario,String origin,long version,String factor)throws Exception {
        var body=new HashMap<String,Object>(Map.of("revisaoOrigemId",origin,"versaoOrigemEsperada",version));
        if(factor!=null) { body.put("fator",factor); }
        return response(actor.post("/api/v1/cenarios/"+scenario.get("cenario").get("id").asText()+"/revisoes",body),201);
    }
    private long count(String table,String revision) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE revisao_id=?",Long.class,Long.valueOf(revision)); }

    @Test
    void submitsFreezesAndPublishesExactSnapshotOnce()throws Exception {
        var scenario=create();String r=rev(scenario);
        assertThat(revision(r).get("submetidoEm").isNull()).isTrue();
        response(admin.post(path(r,"ajustes"),adjustment(0)),201);
        var submitted=submit(r,1);assertThat(submitted.get("estado").asText()).isEqualTo("EM_REVISAO");assertThat(submitted.get("versao").asInt()).isEqualTo(2);
        assertThat(submitted.get("submetidoEm").isNull()).isFalse();assertThat(submitted.get("decididoEm").isNull()).isTrue();
        response(admin.post(path(r,"ajustes"),adjustment(2)),409);response(admin.post(path(r,"submissao"),version(2)),409);
        var result=approver.post(path(r,"aprovacao"),version(2));var approved=response(result,201);String plan=approved.get("planoId").asText();
        assertThat(result.headers().firstValue("Location")).hasValue("/api/v1/planos/"+plan);
        assertThat(approved.get("versaoAtual").asInt()).isEqualTo(3);
        var header=consumer.get("/api/v1/planos/"+plan);assertThat(header.get("aprovadoPor").asText()).isEqualTo(approverId);
        assertThat(header.get("totalLinhas").asInt()).isEqualTo(6);assertThat(header.get("versaoContrato").asText()).isEqualTo("v1");
        var frozen=revision(r);assertThat(frozen.get("estado").asText()).isEqualTo("APROVADA");assertThat(frozen.get("decididoEm")).isEqualTo(header.get("aprovadoEm"));
        var first=consumer.get("/api/v1/planos/"+plan+"/linhas").get("items").get(0);
        assertThat(first.get("quantidadeBase").asText()).isEqualTo("100.0000");assertThat(first.get("quantidade").asText()).isEqualTo("120.0000");
        var conflict=response(approver.post(path(r,"aprovacao"),version(2)),409);assertThat(conflict.get("currentVersion").asInt()).isEqualTo(3);
        response(approver.post(path(r,"aprovacao"),version(3)),409);response(approver.post(path(r,"devolucao"),Map.of("versaoEsperada",3,"motivo","Tarde")),409);
        assertThat(count("plano_publicado",r)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evento_revisao WHERE revisao_id=? AND tipo='APROVACAO'",Long.class,Long.valueOf(r))).isEqualTo(1);
        // An approved revision can seed a new draft, without changing its published plan.
        var child=copy(planner,scenario,r,3,null);assertThat(child.get("estado").asText()).isEqualTo("RASCUNHO");assertThat(child.get("submetidoEm").isNull()).isTrue();
        var childEdit=new HashMap<String,Object>(adjustment(0));childEdit.put("quantidade","999");
        response(planner.post(path(child.get("id").asText(),"ajustes"),childEdit),201);
        assertThat(consumer.get("/api/v1/planos/"+plan+"/linhas").get("items").get(0)).isEqualTo(first);
    }
    @Test
    void returnsWithReasonAndCorrectsInANewRevision()throws Exception {
        var scenario=create();String r=rev(scenario);submit(r,0);
        for(String reason:List.of(""," \t\n ","\u00a0","x".repeat(1001))) {
            response(approver.post(path(r,"devolucao"),Map.of("versaoEsperada",1,"motivo",reason)),422);
        }
        var returned=response(approver.post(path(r,"devolucao"),Map.of("versaoEsperada",1,"motivo"," Rever campanha. ")),200);
        assertThat(returned.get("estado").asText()).isEqualTo("DEVOLVIDA");assertThat(returned.get("versao").asInt()).isEqualTo(2);
        assertThat(returned.get("decididoEm").isNull()).isFalse();
        assertThat(jdbc.queryForObject("SELECT motivo FROM evento_revisao WHERE revisao_id=? AND tipo='DEVOLUCAO'",String.class,Long.valueOf(r))).isEqualTo("Rever campanha.");
        response(admin.post(path(r,"ajustes"),adjustment(2)),409);response(admin.post(path(r,"submissao"),version(2)),409);response(approver.post(path(r,"aprovacao"),version(2)),409);
        var child=copy(planner,scenario,r,2,null);String corrected=child.get("id").asText();
        response(planner.post(path(corrected,"ajustes"),adjustment(0)),201);submit(corrected,1);approve(corrected,2);
        assertThat(revision(r)).isEqualTo(returned);assertThat(count("plano_publicado",r)).isZero();assertThat(count("plano_publicado",corrected)).isEqualTo(1);
    }
    @Test
    void adminDoesNotBypassApprovalAndCreatorCannotDecideEvenWithApproverRole()throws Exception {
        String r=rev(create(dual,execution));submit(r,0);
        response(admin.post(path(r,"aprovacao"),version(1)),403);
        response(dual.post(path(r,"aprovacao"),version(1)),403);
        response(dual.post(path(r,"devolucao"),Map.of("versaoEsperada",1,"motivo","Rever")),403);
        assertThat(revision(r).get("versao").asInt()).isEqualTo(1);assertThat(count("evento_revisao",r)).isEqualTo(2);
        approve(r,1);
    }
    @Test
    void anAuthorOfLocalAdjustmentsCannotDecide()throws Exception {
        String r=rev(create());var adjusted=response(dual.post(path(r,"ajustes"),adjustment(0)),201);
        assertThat(adjusted.get("ajuste").get("autorId").asText()).isEqualTo(dualId);submit(r,1);
        response(dual.post(path(r,"aprovacao"),version(2)),403);
        response(dual.post(path(r,"devolucao"),Map.of("versaoEsperada",2,"motivo","Rever")),403);
        approve(r,2);
    }
    @Test
    void inheritedAdjustmentsAlsoPreventApprovalAcrossMultipleCopies()throws Exception {
        var scenario=create();String root=rev(scenario);response(dual.post(path(root,"ajustes"),adjustment(0)),201);
        var child=copy(planner,scenario,root,1,null);var grandchild=copy(admin,scenario,child.get("id").asText(),0,null);String r=grandchild.get("id").asText();submit(r,0);
        assertThat(grandchild.get("totalAjustesLocais").asInt()).isZero();
        response(dual.post(path(r,"aprovacao"),version(1)),403);approve(r,1);
    }
    @Test
    void ignoresAdjustmentsAfterCopyAndRespectsFactorResetBoundaries()throws Exception {
        var scenario=create();String root=rev(scenario);String child=copy(planner,scenario,root,0,null).get("id").asText();
        response(dual.post(path(root,"ajustes"),adjustment(0)),201);
        submit(child,0);response(dual.post(path(child,"aprovacao"),version(1)),201);
        String reset=copy(planner,scenario,root,1,"1.2").get("id").asText();submit(reset,0);
        response(dual.post(path(reset,"aprovacao"),version(1)),201);
    }
    @Test
    void concurrentApprovalsPublishExactlyOnePlan()throws Exception {
        String r=rev(create());submit(r,0);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();return approver.post(path(r,"aprovacao"),version(1)).statusCode();});
            var second=pool.submit(()->{start.await();return other.post(path(r,"aprovacao"),version(1)).statusCode();});
            start.countDown();assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(201,409);
        } finally { pool.shutdownNow(); }
        assertThat(count("plano_publicado",r)).isEqualTo(1);assertThat(count("linha_plano",r)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evento_revisao WHERE revisao_id=? AND tipo='APROVACAO'",Long.class,Long.valueOf(r))).isEqualTo(1);
    }
    @Test
    void concurrentApprovalAndReturnHaveOnlyOneDecision()throws Exception {
        String r=rev(create());submit(r,0);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return approver.post(path(r,"aprovacao"),version(1)).statusCode();});
            var b=pool.submit(()->{start.await();return other.post(path(r,"devolucao"),Map.of("versaoEsperada",1,"motivo","Rever")).statusCode();});
            start.countDown();var statuses=List.of(a.get(),b.get());
            assertThat(statuses.stream().filter(s->s==409).count()).isEqualTo(1);assertThat(statuses).anyMatch(s->s==200 || s==201);
        } finally { pool.shutdownNow(); }
        String state=revision(r).get("estado").asText();assertThat(state).isIn("APROVADA","DEVOLVIDA");
        assertThat(count("plano_publicado",r)).isEqualTo(state.equals("APROVADA")?1:0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evento_revisao WHERE revisao_id=? AND tipo IN ('APROVACAO','DEVOLUCAO')",Long.class,Long.valueOf(r))).isEqualTo(1);
    }
    @Test
    void adjustmentAndSubmissionCannotCrossTheFreezeBoundary()throws Exception {
        String r=rev(create());var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return planner.post(path(r,"ajustes"),adjustment(0)).statusCode();});
            var b=pool.submit(()->{start.await();return admin.post(path(r,"submissao"),version(0)).statusCode();});
            start.countDown();int adjusted=a.get(),submitted=b.get();
            assertThat(List.of(adjusted,submitted).stream().filter(s->s==409).count()).isEqualTo(1);
            assertThat(revision(r).get("versao").asInt()).isEqualTo(1);
            if(submitted==200) { assertThat(revision(r).get("estado").asText()).isEqualTo("EM_REVISAO");assertThat(count("ajuste",r)).isZero(); }
            else { assertThat(adjusted).isEqualTo(201);assertThat(revision(r).get("estado").asText()).isEqualTo("RASCUNHO");assertThat(count("ajuste",r)).isEqualTo(1); }
        } finally { pool.shutdownNow(); }
    }
    @Test
    void failedSnapshotRollsBackApprovalStateEventAndPlanThenAllowsRetry()throws Exception {
        String r=rev(create());submit(r,0);
        jdbc.execute("ALTER TABLE linha_plano ADD CONSTRAINT test_plan_rollback CHECK (revisao_id<>"+Long.parseLong(r)+" OR quantidade_aprovada<0)");
        // The artificial storage failure is a server error, not a business/version conflict.
        try { response(approver.post(path(r,"aprovacao"),version(1)),500); }
        finally { jdbc.execute("ALTER TABLE linha_plano DROP CHECK test_plan_rollback"); }
        var unchanged=revision(r);assertThat(unchanged.get("estado").asText()).isEqualTo("EM_REVISAO");assertThat(unchanged.get("versao").asInt()).isEqualTo(1);
        assertThat(unchanged.get("decididoEm").isNull()).isTrue();assertThat(count("plano_publicado",r)).isZero();assertThat(count("linha_plano",r)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evento_revisao WHERE revisao_id=? AND tipo='APROVACAO'",Long.class,Long.valueOf(r))).isZero();approve(r,1);
    }
    @Test
    void csvMatchesEveryApiPageEscapesTextAndUsesImmutableSnapshot()throws Exception {
        long source=newExecution(1,69);String r=rev(create(admin,source));submit(r,0);String plan=approve(r,1).get("planoId").asText();
        var api=new ArrayList<JsonNode>();
        for(int page=0;page<2;page++) { for(var line:consumer.get("/api/v1/planos/"+plan+"/linhas?size=200&page="+page).get("items")) { api.add(line); } }
        assertThat(api).hasSize(213);
        var csv=consumer.request("GET","/api/v1/planos/"+plan+"/exportacao.csv",null,false);assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).contains("text/csv","UTF-8");
        assertThat(csv.headers().firstValue("Content-Disposition").orElse("")).contains("plano-"+plan+".csv");
        try(var parser=CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader().setSkipHeaderRecord(true).get().parse(new StringReader(csv.body()))) {
            assertThat(parser.getHeaderNames()).containsExactly("plano_id","produto","periodo","unidade","quantidade_base","quantidade");
            var rows=parser.getRecords();assertThat(rows).hasSize(api.size());
            for(int i=0;i<rows.size();i++) {
                var line=api.get(i);assertThat(rows.get(i).toList()).containsExactly(plan,line.get("produto").asText(),line.get("periodo").asText(),line.get("unidade").asText(),line.get("quantidadeBase").asText(),line.get("quantidade").asText());
            }
        }
        assertThat(api).anyMatch(l->l.get("unidade").asText().equals(UNIT));
        // Out-of-band changes in fixtures prove that downloads don't re-read mutable sources.
        jdbc.update("UPDATE produto SET codigo='CHANGED_CODE',unidade='changed' WHERE id=?",zero);
        jdbc.update("UPDATE linha_previsao SET quantidade_base=999 WHERE execucao_id=?",source);
        jdbc.update("UPDATE linha_cenario SET quantidade_final=999 WHERE revisao_id=?",Long.valueOf(r));
        try { assertThat(consumer.request("GET","/api/v1/planos/"+plan+"/exportacao.csv",null,false).body()).isEqualTo(csv.body()); }
        finally { jdbc.update("UPDATE produto SET codigo='PLAN_Z',unidade=? WHERE id=?",UNIT,zero); }
    }
    @Test
    void enforcesRolesCsrfReadOnlyPlansAndValidationWithoutExposingDrafts()throws Exception {
        String draft=rev(create());String r=rev(create());submit(r,0);String plan=approve(r,1).get("planoId").asText();
        response(new Browser().request("GET","/api/v1/planos",null,false),401);
        assertThat(new Browser().request("GET","/api/v1/planos/"+plan+"/exportacao.csv",null,false).statusCode()).isEqualTo(401);
        response(consumer.request("GET","/api/v1/revisoes/"+draft,null,false),403);
        for(Browser denied:List.of(consumer,commercial,approver)) { response(denied.post(path(draft,"submissao"),version(0)),403); }
        response(planner.post(path(draft,"aprovacao"),version(0)),403);
        response(admin.request("POST",path(draft,"submissao"),version(0),false),403);
        for(Browser reader:List.of(admin,planner,approver,consumer,commercial)) { assertThat(reader.get("/api/v1/planos/"+plan).get("id").asText()).isEqualTo(plan); }
        for(var item:consumer.get("/api/v1/planos?size=200").get("items")) { assertThat(item.get("revisaoId").asText()).isNotEqualTo(draft); }
        response(admin.request("DELETE","/api/v1/planos/"+plan,null,true),403);response(admin.request("PATCH","/api/v1/planos/"+plan,Map.of("quantidade","1"),true),403);
        response(admin.post(path(draft,"submissao"),Map.of()),422);response(admin.post(path(draft,"submissao"),version(-1)),422);
        response(admin.post(path("999999999","submissao"),version(0)),404);
        response(consumer.request("GET","/api/v1/planos?size=201",null,false),422);
        response(consumer.request("GET","/api/v1/planos/"+plan+"/linhas?page=-1",null,false),422);
        assertThat(consumer.get("/api/v1/planos/"+plan+"/linhas?size=1").get("totalPages").asInt()).isEqualTo(6);
        for(String suffix:List.of("","/linhas","/exportacao.csv")) { response(consumer.request("GET","/api/v1/planos/999999999"+suffix,null,false),404); }
    }
    @Test
    void rejectsMissingCellsBeforeSubmissionAndApproval()throws Exception {
        String incomplete=rev(create());jdbc.update("DELETE FROM linha_cenario WHERE revisao_id=? AND produto_id=? AND periodo='2026-11-01'",Long.valueOf(incomplete),product);
        response(admin.post(path(incomplete,"submissao"),version(0)),409);assertThat(revision(incomplete).get("versao").asInt()).isZero();
        String submitted=rev(create());submit(submitted,0);jdbc.update("DELETE FROM linha_cenario WHERE revisao_id=?",Long.valueOf(submitted));
        response(approver.post(path(submitted,"aprovacao"),version(1)),409);assertThat(count("plano_publicado",submitted)).isZero();
    }
}
