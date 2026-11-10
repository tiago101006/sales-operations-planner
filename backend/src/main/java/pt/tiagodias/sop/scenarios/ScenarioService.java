package pt.tiagodias.sop.scenarios;

import java.math.*;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.forecasting.ForecastQueries;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import pt.tiagodias.sop.shared.VersionConflictException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static pt.tiagodias.sop.scenarios.ScenarioDtos.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class ScenarioService {
    private static final BigDecimal MAX=new BigDecimal("99999999999999.9999");
    private final JdbcTemplate jdbc;
    private final ForecastQueries forecasts;
    private final ScenarioQueries queries;
    private final UtilizadorRepository users;
    private final JsonMapper json=JsonMapper.builder().build();
    public ScenarioService(JdbcTemplate jdbc,ForecastQueries forecasts,ScenarioQueries queries,UtilizadorRepository users) {
        this.jdbc=jdbc;this.forecasts=forecasts;this.queries=queries;this.users=users;
    }
    private record Row(long product,java.time.LocalDate period,BigDecimal base,BigDecimal calculated,BigDecimal value) {}
    private record Locked(long id,long scenario,long execution,BigDecimal factor,String state,long version,JsonNode assumptions) {}
    private long insert(String sql,Object... values) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var ps=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<values.length;i++) { ps.setObject(i+1,values[i]); }
            return ps;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private long author(String email) { return users.findByEmail(email).orElseThrow().getId(); }
    private boolean content(String value) { return value!=null && value.codePoints().anyMatch(c->!Character.isWhitespace(c) && !Character.isSpaceChar(c)); }
    private void factor(TipoCenario type,BigDecimal factor) {
        if((type==TipoCenario.BASE && factor.compareTo(BigDecimal.ONE)!=0)
                || (type==TipoCenario.OTIMISTA && factor.compareTo(BigDecimal.ONE)<=0)
                || (type==TipoCenario.CONSERVADOR && factor.compareTo(BigDecimal.ONE)>=0)) {
            throw invalid("BASE exige fator 1; OTIMISTA exige fator > 1; CONSERVADOR exige fator entre 0 e 1 exclusivo no limite superior.");
        }
    }
    private BigDecimal calculate(BigDecimal base,BigDecimal factor) {
        var value=base.multiply(factor).setScale(4,RoundingMode.HALF_UP);
        if(value.compareTo(MAX)>0) { throw invalid("O fator gera uma quantidade acima do limite DECIMAL(18,4)."); }
        return value;
    }
    private Locked lock(long id) {
        return jdbc.query("SELECT * FROM revisao_cenario WHERE id=? FOR UPDATE",(rs,n)->new Locked(rs.getLong("id"),rs.getLong("cenario_id"),rs.getLong("execucao_id"),
                rs.getBigDecimal("fator"),rs.getString("estado"),rs.getLong("versao_lock"),json.readTree(rs.getString("pressupostos"))),id)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Revisão não encontrada."));
    }
    private void version(Locked revision,long expected) { if(revision.version()!=expected) { throw new VersionConflictException(revision.version()); } }
    private void lines(long revision,long execution,List<Row> rows) {
        jdbc.batchUpdate("INSERT INTO linha_cenario(revisao_id,execucao_id,produto_id,periodo,quantidade_calculada,quantidade_final) VALUES (?,?,?,?,?,?)",rows,500,(ps,row)->{
            ps.setLong(1,revision);ps.setLong(2,execution);ps.setLong(3,row.product());ps.setObject(4,row.period());
            ps.setBigDecimal(5,row.calculated());ps.setBigDecimal(6,row.value());
        });
    }
    private long event(long revision,String type,long author,String reason) {
        return insert("INSERT INTO evento_revisao(revisao_id,tipo,autor_id,motivo) VALUES (?,?,?,?)",revision,type,author,reason);
    }
    public CenarioCriado create(CreateCenario request,String email) {
        if(!content(request.nome()) || !content(request.pressupostos().descricao())) { throw invalid("Nome e pressupostos exigem conteúdo."); }
        factor(request.tipo(),request.fator());
        var execution=forecasts.get(request.execucaoId());
        if(!execution.estado().equals("CONCLUIDA") || execution.totalLinhas()==0) { throw conflict("A execução deve estar concluída e conter previsões."); }
        var source=jdbc.query("SELECT * FROM linha_previsao WHERE execucao_id=? ORDER BY produto_id,periodo",(rs,n)->{
            var base=rs.getBigDecimal("quantidade_base");var value=calculate(base,request.fator());
            return new Row(rs.getLong("produto_id"),rs.getDate("periodo").toLocalDate(),base,value,value);
        },request.execucaoId());
        long author=author(email);
        long scenario=insert("INSERT INTO cenario(execucao_id,nome,tipo,criado_por) VALUES (?,?,?,?)",request.execucaoId(),request.nome().strip(),request.tipo().name(),author);
        String assumptions=json.writeValueAsString(Map.of("descricao",request.pressupostos().descricao().strip(),"herdaAjustes",false));
        long revision=insert("INSERT INTO revisao_cenario(cenario_id,execucao_id,numero,fator,pressupostos,criado_por) VALUES (?,?,1,?,?,?)",scenario,request.execucaoId(),request.fator().setScale(6),assumptions,author);
        lines(revision,request.execucaoId(),source);
        event(revision,"CRIACAO",author,"Cenário criado a partir da execução "+request.execucaoId());
        return new CenarioCriado(queries.cenario(scenario),queries.revisao(revision));
    }
    public RevisaoDto revise(long scenario,CreateRevisao request,String email) {
        var parents=jdbc.queryForList("SELECT id,tipo FROM cenario WHERE id=? FOR UPDATE",scenario);
        if(parents.isEmpty()) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cenário não encontrado."); }
        var original=lock(request.revisaoOrigemId());
        if(original.scenario()!=scenario) { throw conflict("A revisão de origem pertence a outro cenário."); }
        version(original,request.versaoOrigemEsperada());
        var newFactor=request.fator()==null?original.factor():request.fator().setScale(6);
        factor(TipoCenario.valueOf(parents.get(0).get("tipo").toString()),newFactor);
        boolean inherit=newFactor.compareTo(original.factor())==0;
        String description=request.pressupostos()==null?original.assumptions().path("descricao").asText():request.pressupostos().descricao();
        if(!content(description)) { throw invalid("Os pressupostos exigem conteúdo."); }
        var rows=jdbc.query("SELECT l.*,b.quantidade_base FROM linha_cenario l JOIN linha_previsao b ON b.execucao_id=l.execucao_id AND b.produto_id=l.produto_id AND b.periodo=l.periodo WHERE l.revisao_id=? ORDER BY l.produto_id,l.periodo",(rs,n)->{
            var base=rs.getBigDecimal("quantidade_base");
            var calculated=inherit?rs.getBigDecimal("quantidade_calculada"):calculate(base,newFactor);
            var value=inherit?rs.getBigDecimal("quantidade_final"):calculated;
            return new Row(rs.getLong("produto_id"),rs.getDate("periodo").toLocalDate(),base,calculated,value);
        },original.id());
        if(rows.isEmpty()) { throw conflict("A revisão de origem não contém linhas."); }
        var assumptions=new LinkedHashMap<String,Object>();
        assumptions.put("descricao",description.strip());assumptions.put("herdaAjustes",inherit);
        assumptions.put("versaoOrigemCopiada",original.version());
        if(inherit) { assumptions.put("ajusteOrigemAteId",jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM ajuste",Long.class).toString()); }
        int number=jdbc.queryForObject("SELECT COALESCE(MAX(numero),0)+1 FROM revisao_cenario WHERE cenario_id=?",Integer.class,scenario);
        long author=author(email);
        long id=insert("INSERT INTO revisao_cenario(cenario_id,execucao_id,numero,revisao_origem_id,fator,pressupostos,criado_por) VALUES (?,?,?,?,?,?,?)",
                scenario,original.execution(),number,original.id(),newFactor,json.writeValueAsString(assumptions),author);
        lines(id,original.execution(),rows);
        event(id,"CRIACAO",author,"Nova revisão de "+original.id()+" na versão "+original.version()+(inherit?"; valores e ajustes herdados.":"; valores recalculados com novo fator."));
        return queries.revisao(id);
    }
    public AjusteCriado adjust(long revision,CreateAjuste request,String email) {
        var locked=lock(revision);version(locked,request.versaoEsperada());
        if(!locked.state().equals("RASCUNHO")) { throw conflict("Apenas revisões em RASCUNHO aceitam ajustes."); }
        if(!content(request.justificacao())) { throw invalid("A justificação exige conteúdo."); }
        var period=YearMonth.parse(request.periodo()).atDay(1);
        var values=jdbc.query("SELECT quantidade_final FROM linha_cenario WHERE revisao_id=? AND produto_id=? AND periodo=?",(rs,n)->rs.getBigDecimal(1),revision,request.produtoId(),period);
        if(values.isEmpty()) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"A célula não pertence a esta revisão."); }
        String reason=request.justificacao().strip();long author=author(email);
        long event=event(revision,"AJUSTE",author,reason);
        int sequence=jdbc.queryForObject("SELECT COALESCE(MAX(sequencia),0)+1 FROM ajuste WHERE revisao_id=? AND produto_id=? AND periodo=?",Integer.class,revision,request.produtoId(),period);
        long id=insert("INSERT INTO ajuste(revisao_id,produto_id,periodo,sequencia,evento_id,valor_anterior,valor_novo,justificacao) VALUES (?,?,?,?,?,?,?,?)",
                revision,request.produtoId(),period,sequence,event,values.get(0),request.quantidade().setScale(4),reason);
        jdbc.update("UPDATE linha_cenario SET quantidade_final=? WHERE revisao_id=? AND produto_id=? AND periodo=?",request.quantidade().setScale(4),revision,request.produtoId(),period);
        jdbc.update("UPDATE revisao_cenario SET versao_lock=versao_lock+1 WHERE id=?",revision);
        return new AjusteCriado(queries.ajuste(id,revision),locked.version()+1);
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
