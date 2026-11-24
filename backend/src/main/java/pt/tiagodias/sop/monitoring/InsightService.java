package pt.tiagodias.sop.monitoring;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import pt.tiagodias.sop.imports.ImportQueries;
import tools.jackson.databind.json.JsonMapper;
import static pt.tiagodias.sop.monitoring.InsightDtos.*;

@Service
public class InsightService {
    private static final int RETRY_SECONDS=30;
    private final MonitoringQueries queries;
    private final JdbcTemplate jdbc;
    private final UtilizadorRepository users;
    private final InsightSettings settings;
    private final LocalInsightGenerator generator;
    private final JsonMapper json;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1), task -> {
        var thread=new Thread(task,"nexoplan-local-analysis");thread.setDaemon(true);return thread;
    });
    public InsightService(MonitoringQueries queries,JdbcTemplate jdbc,UtilizadorRepository users,
                          InsightSettings settings,LocalInsightGenerator generator,JsonMapper json) {
        this.queries=queries;this.jdbc=jdbc;this.users=users;this.settings=settings;this.generator=generator;this.json=json;
    }
    private record Stored(Relatorio response,Instant requested) {}

    public Relatorio get(long id) {
        queries.get(id);
        expire(id);
        var existing=stored(id);
        return existing==null ? idle() : existing.response();
    }
    public synchronized Relatorio start(long id,String email) {
        queries.get(id);
        expire(id);
        var existing=stored(id);
        if (existing!=null && !existing.response().estado().equals("FALHOU")) return existing.response();
        if (!settings.enabled()) return idle();
        if (existing!=null && existing.requested().isAfter(Instant.now().minusSeconds(RETRY_SECONDS))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Aguarda 30 segundos antes de repetir a análise.");
        }
        if (!busy.compareAndSet(false,true)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "O modelo local já está a analisar outra avaliação. Aguarda a conclusão e tenta novamente.");
        boolean scheduled=false;
        String token=UUID.randomUUID().toString();
        try {
            // The repository's transaction ends here, before contacting the model.
            Snapshot snapshot=queries.insightSnapshot(id);
            String evidence=InsightPrompt.evidence(snapshot,json),data=json.writeValueAsString(snapshot),hash=sha256(data);
            if (data.getBytes(StandardCharsets.UTF_8).length>8*1024*1024) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "O detalhe desta avaliação excede o limite da análise local. Usa uma janela menor; nenhum dado foi omitido.");
            long author=users.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
            var now=Timestamp.from(Instant.now());
            if (existing==null) {
                try {
                    jdbc.update("INSERT INTO analise_inteligente(avaliacao_id,estado,modelo,versao_prompt,hash_dados,dados_json,solicitado_em,criado_por,execucao_token) VALUES (?,'EM_CURSO',?,?,?,?,?,?,?)",
                            id,settings.model(),InsightPrompt.VERSION,hash,data,now,author,token);
                } catch (DuplicateKeyException claimed) { return get(id); }
            } else {
                int changed=jdbc.update("UPDATE analise_inteligente SET estado='EM_CURSO',modelo=?,versao_prompt=?,hash_dados=?,dados_json=?,analise_json=NULL,mensagem=NULL,solicitado_em=?,gerado_em=NULL,criado_por=?,execucao_token=? WHERE avaliacao_id=? AND estado='FALHOU'",
                        settings.model(),InsightPrompt.VERSION,hash,data,now,author,token,id);
                if (changed!=1) return get(id);
            }
            try {
                worker.execute(() -> generate(id,token,evidence,snapshot));
                scheduled=true;
            } catch (RejectedExecutionException stopped) {
                failed(id,token,"A aplicação está a reiniciar. Tenta novamente dentro de instantes.");
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"A análise local está temporariamente indisponível.");
            }
            return get(id);
        } finally { if (!scheduled) busy.set(false); }
    }
    private void generate(long id,String token,String evidence,Snapshot snapshot) {
        try {
            var catalog=GroundedInsightReport.recommendations(snapshot);
            var order=generator.generate(evidence,catalog);
            var report=GroundedInsightReport.compose(snapshot,order);
            String reportJson=InsightReportCache.encode(report,order,json);
            if(reportJson.getBytes(StandardCharsets.UTF_8).length>128*1024)
                throw new LocalInsightGenerator.InsightFailure("O relatório completo excede o tamanho permitido. Usa uma avaliação com uma janela menor; não foi guardado texto parcial.");
            jdbc.update("UPDATE analise_inteligente SET estado='CONCLUIDA',analise_json=?,gerado_em=?,mensagem=NULL WHERE avaliacao_id=? AND execucao_token=? AND estado='EM_CURSO'",
                    reportJson,Timestamp.from(Instant.now()),id,token);
        } catch (LocalInsightGenerator.InsightFailure error) {
            failed(id,token,error.getMessage());
        } catch (Exception error) {
            // Never expose provider bodies, prompts, connection details or exceptions to the browser.
            failed(id,token,"Não foi possível concluir a análise local. Podes tentar novamente.");
        } finally { busy.set(false); }
    }
    private void failed(long id,String token,String message) {
        jdbc.update("UPDATE analise_inteligente SET estado='FALHOU',mensagem=? WHERE avaliacao_id=? AND execucao_token=? AND estado='EM_CURSO'",message,id,token);
    }
    private void expire(long id) {
        jdbc.update("UPDATE analise_inteligente SET estado='FALHOU',mensagem=? WHERE avaliacao_id=? AND estado='EM_CURSO' AND solicitado_em<?",
                "A geração foi interrompida ou ultrapassou o tempo limite. Podes voltar a gerar a análise.",id,
                Timestamp.from(Instant.now().minusSeconds(settings.timeoutSeconds()+30L)));
    }
    private Stored stored(long id) {
        return jdbc.query("SELECT * FROM analise_inteligente WHERE avaliacao_id=?",(rs,index) -> {
            boolean complete=rs.getString("estado").equals("CONCLUIDA");
            var snapshot=complete ? json.readValue(rs.getString("dados_json"),Snapshot.class) : null;
            var analysis=complete ? InsightReportCache.read(snapshot,rs.getString("analise_json"),json) : null;
            return new Stored(new Relatorio(rs.getString("estado"),rs.getString("mensagem"),ImportQueries.instant(rs,"gerado_em"),
                    rs.getString("modelo"),rs.getString("versao_prompt"),rs.getString("hash_dados"),analysis,
                    snapshot==null?List.of():snapshot.produtos()),ImportQueries.instant(rs,"solicitado_em"));
        },id).stream().findFirst().orElse(null);
    }
    private Relatorio idle() {
        return new Relatorio(settings.enabled()?"POR_GERAR":"DESATIVADA",
                settings.enabled()?null:"A análise com IA local ainda não está ativada nesta instalação.",null,
                settings.model(),InsightPrompt.VERSION,null,null,List.of());
    }
    static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @PreDestroy void stop() { worker.shutdownNow(); }
}
