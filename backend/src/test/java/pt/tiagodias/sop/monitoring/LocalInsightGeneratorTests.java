package pt.tiagodias.sop.monitoring;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static pt.tiagodias.sop.monitoring.InsightDtos.*;
import static pt.tiagodias.sop.monitoring.MonitoringDtos.*;

class LocalInsightGeneratorTests {
    private final JsonMapper json=JsonMapper.builder().build();
    private HttpServer server;
    private LocalInsightGenerator generator;
    private final AtomicReference<String> reply=new AtomicReference<>();
    private final AtomicReference<String> request=new AtomicReference<>();
    private volatile int status=200;
    private volatile int delayMillis;
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/generate",exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            if (delayMillis>0) try { Thread.sleep(delayMillis); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            byte[] body=reply.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,body.length);
            try(var output=exchange.getResponseBody()) { output.write(body); }
        });server.start();
        generator=new LocalInsightGenerator(new InsightSettings(true,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"qwen3.5:4b-q4_K_M",5),json);
    }
    @AfterEach void stop() { server.stop(0); }
    private final List<GroundedInsightReport.Recommendation> catalog=List.of(
            new GroundedInsightReport.Recommendation("CONFERIR", "Conferir os dados", "Ação calculada da aplicação."),
            new GroundedInsightReport.Recommendation("PRESERVAR", "Preservar o plano", "Outra ação calculada."));
    private final List<String> validOrder=List.of("PRESERVAR","CONFERIR");
    private String result(String text,String reason,int tokens) {
        return json.writeValueAsString(Map.of("done",true,"done_reason",reason,"response",text,"prompt_eval_count",tokens));
    }
    private String providerOrder(List<String> order) { return json.writeValueAsString(Map.of("prioridades",order)); }
    @Test void sendsOnlyToLocalServerAndRequiresCompleteStructuredOutput() {
        reply.set(result(providerOrder(validOrder),"stop",700));
        assertThat(generator.generate("{\"produtos\":[]}",catalog)).isEqualTo(validOrder);
        var sent=json.readTree(request.get());
        assertThat(sent.get("stream").asBoolean()).isFalse();assertThat(sent.get("keep_alive").asInt()).isZero();
        assertThat(sent.get("truncate").asBoolean()).isFalse();assertThat(sent.get("shift").asBoolean()).isFalse();
        assertThat(sent.has("think")).isTrue();assertThat(sent.get("think").asBoolean()).isFalse();
        assertThat(sent.get("model").asText()).isEqualTo("qwen3.5:4b-q4_K_M");
        assertThat(sent.get("options").get("num_ctx").asInt()).isEqualTo(16384);
        assertThat(sent.get("options").get("num_thread").asInt()).isEqualTo(3);
        assertThat(sent.get("system").asText()).contains("null significa apenas indicador indisponível","Não inventes metas, causas");
        assertThat(sent.has("tools")).isFalse();
        assertThat(sent.get("options").get("num_predict").asInt()).isEqualTo(1200);
    }
    @Test void rejectsTruncationOversizeInvalidOutputAndUpstreamErrorsWithoutLeakingProviderBody() {
        for (String invalid : List.of(result(providerOrder(validOrder),"length",700),
                result(providerOrder(validOrder),"stop",16_000),
                result("{\"titulo\":\"x\",\"secoes\":[]}","stop",700),"x".repeat(150_000))) {
            reply.set(invalid);assertThatThrownBy(() -> generator.generate("evidence",catalog))
                    .isInstanceOf(LocalInsightGenerator.InsightFailure.class).hasMessageNotContaining("evidence");
        }
        status=503;reply.set("private-provider-detail");
        assertThatThrownBy(() -> generator.generate("private-evidence",catalog))
                .isInstanceOf(LocalInsightGenerator.InsightFailure.class).hasMessageNotContaining("private-provider-detail");
    }
    @Test void refusesRemoteCloudUrlsAndRedirectCapableConfigurations() {
        for (String url : List.of("https://api.example.com","http://localhost@outside.invalid","http://localhost/path","http://localhost?url=outside")) {
            assertThatThrownBy(() -> new InsightSettings(true,URI.create(url),"qwen3.5:4b-q4_K_M",5)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new InsightSettings(true,URI.create("http://localhost"),"remote-cloud",5)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsRepeatedMissingUnknownIdsAndFreeProseWithoutFallback() {
        for(String invalid:List.of(providerOrder(List.of("CONFERIR","CONFERIR")),
                providerOrder(List.of("CONFERIR")),providerOrder(List.of("CONFERIR","INVENTADO")),
                "{\"prioridades\":[\"CONFERIR\",\"PRESERVAR\"],\"justificacao\":\"causa inventada\"}",
                "{\"prioridades\":[42,\"PRESERVAR\"]}", "{\"prioridades\":[]}")) {
            assertThatThrownBy(() -> generator.validate(invalid,catalog)).isInstanceOf(LocalInsightGenerator.InsightFailure.class);
        }
        assertThat(generator.validate(providerOrder(validOrder),catalog)).containsExactly("PRESERVAR","CONFERIR");
    }
    @Test void rejectsServerContextErrorsWithoutRetryingWithTruncationOrReturningPartialText() {
        status=400;reply.set("{\"error\":\"the input length exceeds the model context window\"}");
        assertThatThrownBy(() -> generator.generate("complete-evidence",catalog))
                .isInstanceOf(LocalInsightGenerator.InsightFailure.class).hasMessageContaining("recusou o pedido").hasMessageNotContaining("input length");
        var sent=json.readTree(request.get());
        assertThat(sent.get("prompt").asText()).contains("complete-evidence");
        assertThat(sent.get("truncate").asBoolean()).isFalse();
        assertThat(sent.get("shift").asBoolean()).isFalse();
    }
    @Test void semanticFactsDistinguishTemporalExclusionsFromMissingOutcomes() {
        var late=new Celula("1","SKU001","un","2026-11",1,"22.3333","30.0000",null,null,null,false,false,
                "PLANO_PUBLICADO_TARDIAMENTE",null,"PLANO_PUBLICADO_TARDIAMENTE");
        var open=new Celula("1","SKU001","un","2026-12",2,"22.1111","24.3222",null,null,null,false,false,
                "PERIODO_NAO_FECHADO",null,"PERIODO_NAO_FECHADO");
        var january=new Celula("1","SKU001","un","2027-01",3,"22.8148","25.0963",null,null,null,false,false,
                "PERIODO_NAO_FECHADO",null,"PERIODO_NAO_FECHADO");
        var cells=List.of(late,open,january);
        var snapshot=new Snapshot(new Contexto("2","2","3","4","2026-11","2027-01",Instant.EPOCH,"v1"),
                List.of(new Produto(MonitoringMetrics.precision(cells),MonitoringMetrics.operation(cells),cells)));
        String facts=String.join("\n",InsightPrompt.facts(snapshot));
        assertThat(facts).contains("1 produto(s) e 3 células","0 pares elegíveis para MAE","1 célula(s) por plano publicado após início do mês",
                "2 por período ainda não fechado","0 classificada(s) especificamente como SEM_REALIZADO",
                "2026-11={publicação tardia=1}","2026-12={mês ainda não fechado no corte=1}",
                "Os produtos e as quantidades base/aprovadas existem","independentemente da disponibilidade dos dados");
    }
    @Test void schemaOnlyAllowsCompleteCatalogPermutationsAndEvidenceContainsNoEmployeeText() {
        var schema=json.valueToTree(InsightPrompt.schema(catalog));
        var property=schema.get("properties").get("prioridades");
        assertThat(property.get("minItems").asInt()).isEqualTo(2);
        assertThat(property.get("maxItems").asInt()).isEqualTo(2);
        assertThat(property.get("uniqueItems").asBoolean()).isTrue();
        assertThat(property.get("items").get("enum").toString()).contains("CONFERIR","PRESERVAR");
        var cell=new Celula("1","SKU001","un","2025-01",1,"10","11",null,null,null,true,false,"SEM_REALIZADO",null,"SEM_LOTE_OPERACIONAL");
        var cells=List.of(cell);
        var snapshot=new Snapshot(new Contexto("1","1","1",null,"2025-01","2025-01",Instant.EPOCH,"v1"),
                List.of(new Produto(MonitoringMetrics.precision(cells),MonitoringMetrics.operation(cells),cells)));
        String evidence=InsightPrompt.evidence(snapshot,json);
        assertThat(evidence).doesNotContain("Tarefa:","orientacao_por_seccao","necessário apresentar","não afirmar");
        assertThat(json.readTree(evidence).get("recomendacoes_elegiveis").size()).isEqualTo(GroundedInsightReport.recommendations(snapshot).size());
    }
    @Test void localTimeoutIsFiniteAndDisabledModeNeverCallsTheModel() {
        delayMillis=2000;reply.set(result(providerOrder(validOrder),"stop",700));
        var settings=new InsightSettings(true,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"qwen3.5:4b-q4_K_M",1);
        var impatient=new LocalInsightGenerator(settings,json);long started=System.nanoTime();
        assertThatThrownBy(() -> impatient.generate("evidence",catalog)).isInstanceOf(LocalInsightGenerator.InsightFailure.class);
        assertThat((System.nanoTime()-started)/1_000_000_000.0).isLessThan(3);
        var queries=org.mockito.Mockito.mock(MonitoringQueries.class);
        var provider=org.mockito.Mockito.mock(LocalInsightGenerator.class);
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var users=org.mockito.Mockito.mock(pt.tiagodias.sop.identity.UtilizadorRepository.class);
        var service=new InsightService(queries,jdbc,users,new InsightSettings(false,URI.create("http://localhost"),"qwen3.5:4b-q4_K_M",5),provider,json);
        try {
            assertThat(service.get(1).estado()).isEqualTo("DESATIVADA");
            assertThat(service.start(1,"unused@example.test").estado()).isEqualTo("DESATIVADA");
            org.mockito.Mockito.verifyNoInteractions(provider,users);
            org.mockito.Mockito.verify(queries,org.mockito.Mockito.never()).insightSnapshot(org.mockito.ArgumentMatchers.anyLong());
        } finally { service.stop(); }
    }
    @Test void promptPreservesZeroMissingCoverageAndUnitsWithoutInventedCombinedErrors() {
        var zero=new Celula("1","SKU001","un","2025-01",1,"10.0000","0.0000","1","0.0000",Instant.EPOCH,true,true,null,null,"SEM_LOTE_OPERACIONAL");
        var missing=new Celula("1","SKU001","un","2025-02",2,"20.0000","25.0000",null,null,null,true,false,"SEM_REALIZADO",null,"SEM_LOTE_OPERACIONAL");
        var cells=List.of(zero,missing);
        var snapshot=new Snapshot(new Contexto("1","1","1",null,"2025-01","2025-02",Instant.EPOCH,"v1"),
                List.of(new Produto(MonitoringMetrics.precision(cells),MonitoringMetrics.operation(cells),cells)));
        var evidence=json.readTree(InsightPrompt.evidence(snapshot,json));var row=evidence.get("produtos").get(0);
        assertThat(row.get(0).asText()).isEqualTo("SKU001");assertThat(row.get(1).asText()).isEqualTo("un");
        assertThat(row.get(3).asInt()).isEqualTo(1);assertThat(row.get(4).asInt()).isZero();assertThat(row.get(5).asInt()).isEqualTo(1);
        assertThat(row.get(6).asInt()).isEqualTo(1);assertThat(row.get(10).asText()).isEqualTo("50.0000");
        assertThat(row.get(13).isNull()).isTrue();assertThat(row.get(21).isNull()).isTrue();
        assertThat(evidence.has("maeGlobal")).isFalse();
        assertThat(evidence.toString()).doesNotContain("\"criadoPor\":","\"email\":","\"nome\":","\"observacaoId\":");
    }
    @Test void overBudgetRejectsRatherThanTakingOnlyFirstProducts() {
        var cell=new Celula("1","Z".repeat(40_000),"un","2025-01",1,"1","1",null,null,null,true,false,"SEM_REALIZADO",null,"SEM_LOTE_OPERACIONAL");
        var cells=List.of(cell);
        var snapshot=new Snapshot(new Contexto("1","1","1",null,"2025-01","2025-01",Instant.EPOCH,"v1"),
                List.of(new Produto(MonitoringMetrics.precision(cells),MonitoringMetrics.operation(cells),cells)));
        assertThatThrownBy(() -> InsightPrompt.evidence(snapshot,json)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("nenhum produto foi omitido");
    }
}
