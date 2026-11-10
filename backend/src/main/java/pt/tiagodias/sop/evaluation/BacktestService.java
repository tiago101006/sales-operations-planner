package pt.tiagodias.sop.evaluation;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import pt.tiagodias.sop.imports.ImportQueries;
import tools.jackson.databind.json.JsonMapper;
import static pt.tiagodias.sop.evaluation.BacktestDtos.*;

@Service
public class BacktestService {
    private final JdbcTemplate jdbc;
    private final ImportQueries lots;
    private final UtilizadorRepository users;
    private final BacktestModels models;
    private final BacktestQueries queries;
    private final JsonMapper json=JsonMapper.builder().build();
    public BacktestService(JdbcTemplate jdbc,ImportQueries lots,UtilizadorRepository users,BacktestModels models,BacktestQueries queries) {
        this.jdbc=jdbc;this.lots=lots;this.users=users;this.models=models;this.queries=queries;
    }
    private record Product(long id,String code) {}
    private record Observation(long id,YearMonth month,BigDecimal quantity,Instant available) {}

    @Transactional
    public BacktestDto create(CreateBacktest request,String email) {
        var lot=lots.get(request.loteHistoricoId());
        if(!lot.tipo().equals("HISTORICO") || !lot.estado().equals("VALIDADO")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Seleciona um histórico validado.");
        }
        var first=YearMonth.parse(request.primeiroCorte());var last=YearMonth.parse(request.ultimoCorte());
        long origins=ChronoUnit.MONTHS.between(first,last)+1;
        Instant actualCut=(request.corteRealizados()==null?Instant.now():request.corteRealizados()).truncatedTo(ChronoUnit.MICROS);
        if(origins<1 || origins>24 || actualCut.isAfter(Instant.now()) || last.plusMonths(request.horizonteMeses()).getYear()>9999
                || first.minusMonths(Math.max(request.janelaMediaMovel(),request.historicoEtsMeses())-1L).getYear()<1000
                || cutInstant(last).isAfter(actualCut)) { throw invalid("Usa 1 a 24 cortes mensais ordenados, sem cortes futuros e com datas dentro do intervalo suportado."); }
        var products=jdbc.query("SELECT DISTINCT p.id,p.codigo FROM produto p JOIN observacao o ON p.id=o.produto_id WHERE o.lote_id=? AND o.tipo=? ORDER BY p.id LIMIT 1001",
                (rs,n)->new Product(rs.getLong("id"),rs.getString("codigo")),request.loteHistoricoId(),request.tipoSerie().name());
        long cells=products.size()*origins*request.horizonteMeses();
        if(products.isEmpty() || products.size()>1000 || cells>20000) { throw invalid("Seleciona 1 a 1000 produtos, com máximo de 20000 células produto/corte/horizonte."); }
        var params=Map.of("janelaMediaMovel",request.janelaMediaMovel(),"historicoEtsMeses",request.historicoEtsMeses(),
                "varianteEts","AAN","recursoEts","SEM_RECURSO","corteTreino","INICIO_MES_SEGUINTE_UTC","populacaoMetricas","INTERSECAO_MM_ETS");
        long author=users.findByEmail(email).orElseThrow().getId();var key=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var ps=connection.prepareStatement("INSERT INTO backtest(lote_id,lote_sha256,tipo_serie,primeiro_corte,ultimo_corte,horizonte_meses,corte_realizados,parametros,versao_mm,versao_ets,versao_calculo,estado,criado_por) VALUES (?,?,?,?,?,?,?,?,?,?,?,'EM_EXECUCAO',?)",Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1,request.loteHistoricoId());ps.setString(2,lot.sha256());ps.setString(3,request.tipoSerie().name());
            ps.setObject(4,first.atDay(1));ps.setObject(5,last.atDay(1));ps.setInt(6,request.horizonteMeses());
            ps.setObject(7,utc(actualCut));ps.setString(8,json.writeValueAsString(params));ps.setString(9,models.movingVersion());
            ps.setString(10,models.etsVersion());ps.setString(11,CommonMetrics.VERSION);ps.setLong(12,author);return ps;
        },key);
        long id=Objects.requireNonNull(key.getKey()).longValue();
        for(var product:products) {
            var observations=jdbc.query("SELECT id,periodo,quantidade,disponivel_em FROM observacao WHERE lote_id=? AND produto_id=? AND tipo=? ORDER BY periodo",
                    (rs,n)->new Observation(rs.getLong("id"),YearMonth.from(rs.getDate("periodo").toLocalDate()),rs.getBigDecimal("quantidade"),ImportQueries.instant(rs,"disponivel_em")),
                    request.loteHistoricoId(),product.id(),request.tipoSerie().name());
            var actuals=new TreeMap<YearMonth,Observation>();for(var observation:observations) { actuals.put(observation.month(),observation); }
            for(int fold=0;fold<origins;fold++) {
                YearMonth cut=first.plusMonths(fold);Instant instant=cutInstant(cut);
                var train=new TreeMap<YearMonth,BigDecimal>();
                for(var row:observations) {
                    if(!row.month().isAfter(cut) && !row.available().isAfter(instant)) { train.put(row.month(),row.quantity()); }
                }
                var mm=models.moving(train,cut,request.janelaMediaMovel(),request.horizonteMeses());
                var ets=models.ets(train,cut,request.historicoEtsMeses(),request.horizonteMeses());
                var inserts=new ArrayList<Object[]>();
                for(int h=1;h<=request.horizonteMeses();h++) {
                    YearMonth target=cut.plusMonths(h);Observation actual=actuals.get(target);
                    if(actual!=null && (actual.available().isAfter(actualCut) || target.atEndOfMonth().isAfter(actualCut.atZone(ZoneOffset.UTC).toLocalDate()))) { actual=null; }
                    inserts.add(new Object[]{id,request.loteHistoricoId(),product.id(),cut.atDay(1),target.atDay(1),h,
                            actual==null?null:actual.id(),mm.values().isEmpty()?null:mm.values().get(h-1),ets.values().isEmpty()?null:ets.values().get(h-1),
                            mm.state(),ets.state(),json.writeValueAsString(mm.parameters()),json.writeValueAsString(ets.parameters()),mm.diagnostic(),ets.diagnostic()});
                }
                jdbc.batchUpdate("INSERT INTO backtest_celula(backtest_id,lote_id,produto_id,corte,periodo,horizonte,observacao_id,previsao_mm,previsao_ets,estado_mm,estado_ets,parametros_mm,parametros_ets,diagnostico_mm,diagnostico_ets) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",inserts);
            }
        }
        jdbc.update("UPDATE backtest SET estado='CONCLUIDO',concluido_em=? WHERE id=?",utc(Instant.now().truncatedTo(ChronoUnit.MICROS)),id);
        return queries.get(id);
    }
    public static Instant cutInstant(YearMonth cut) { return cut.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(); }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant,ZoneOffset.UTC); }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,message); }
}
