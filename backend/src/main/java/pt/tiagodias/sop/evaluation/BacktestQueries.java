package pt.tiagodias.sop.evaluation;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.imports.ImportQueries;
import tools.jackson.databind.json.JsonMapper;
import static pt.tiagodias.sop.evaluation.BacktestDtos.*;

@Repository
public class BacktestQueries {
    private final JdbcTemplate jdbc;
    private final ImportQueries lots;
    private final JsonMapper json=JsonMapper.builder().build();
    public BacktestQueries(JdbcTemplate jdbc,ImportQueries lots) { this.jdbc=jdbc;this.lots=lots; }
    private static final String HEADER="SELECT b.*,(SELECT COUNT(*) FROM backtest_celula c WHERE c.backtest_id=b.id) total FROM backtest b ";
    private static final String CELLS="SELECT c.*,p.codigo,p.unidade,o.quantidade realizado FROM backtest_celula c JOIN produto p ON p.id=c.produto_id LEFT JOIN observacao o ON o.id=c.observacao_id WHERE c.backtest_id=? ";
    private BacktestDto header(ResultSet rs,int n)throws SQLException {
        return new BacktestDto(rs.getString("id"),rs.getString("lote_id"),rs.getString("lote_sha256"),rs.getString("tipo_serie"),rs.getString("modo"),
                month(rs,"primeiro_corte"),month(rs,"ultimo_corte"),rs.getInt("horizonte_meses"),ImportQueries.instant(rs,"corte_realizados"),
                json.readTree(rs.getString("parametros")),rs.getString("versao_mm"),rs.getString("versao_ets"),rs.getString("versao_calculo"),
                rs.getString("estado"),rs.getString("criado_por"),ImportQueries.instant(rs,"criado_em"),ImportQueries.instant(rs,"concluido_em"),rs.getLong("total"));
    }
    public BacktestDto get(long id) {
        return jdbc.query(HEADER+"WHERE b.id=?",this::header,id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Backtest não encontrado."));
    }
    public Pagina<BacktestDto> list(int page,int size) {
        int offset=ImportQueries.offset(page,size);long count=jdbc.queryForObject("SELECT COUNT(*) FROM backtest",Long.class);
        return new Pagina<>(jdbc.query(HEADER+"ORDER BY b.id DESC LIMIT ? OFFSET ?",this::header,size,offset),page,size,count,pages(count,size));
    }
    private CelulaDto cell(ResultSet rs,int n)throws SQLException {
        var actual=rs.getBigDecimal("realizado");var mm=rs.getBigDecimal("previsao_mm");var ets=rs.getBigDecimal("previsao_ets");
        boolean comparable=actual!=null && mm!=null && ets!=null;
        var cut=month(rs,"corte");
        return new CelulaDto(rs.getString("produto_id"),rs.getString("codigo"),rs.getString("unidade"),cut,BacktestService.cutInstant(YearMonth.parse(cut)),
                month(rs,"periodo"),rs.getInt("horizonte"),rs.getString("observacao_id"),decimal(actual),decimal(mm),decimal(ets),
                rs.getString("estado_mm"),rs.getString("estado_ets"),rs.getString("diagnostico_mm"),rs.getString("diagnostico_ets"),
                json.readTree(rs.getString("parametros_mm")),json.readTree(rs.getString("parametros_ets")),comparable,comparable && actual.signum()!=0);
    }
    public Pagina<CelulaDto> cells(long id,int page,int size) {
        int offset=ImportQueries.offset(page,size);long count=get(id).totalCelulas();
        return new Pagina<>(jdbc.query(CELLS+"ORDER BY p.codigo,c.corte,c.horizonte LIMIT ? OFFSET ?",this::cell,id,size,offset),page,size,count,pages(count,size));
    }
    private record Key(String id,String code,String unit,int horizon) {}
    public Pagina<GrupoDto> comparison(long id,int page,int size) {
        int offset=ImportQueries.offset(page,size);
        if(!get(id).versaoCalculo().equals(CommonMetrics.VERSION)) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Versão de cálculo não suportada."); }
        var data=jdbc.query(CELLS+"ORDER BY p.codigo,c.horizonte,c.corte",this::cell,id);
        var grouped=new LinkedHashMap<Key,List<CommonMetrics.Pair>>();
        for(var row:data) {
            var key=new Key(row.produtoId(),row.produto(),row.unidade(),row.horizonte());
            grouped.computeIfAbsent(key,k->new ArrayList<>()).add(new CommonMetrics.Pair(number(row.previsaoMediaMovel()),number(row.previsaoEts()),number(row.realizado())));
        }
        var groups=new ArrayList<GrupoDto>();
        grouped.forEach((key,pairs)->groups.add(new GrupoDto(key.id(),key.code(),key.unit(),key.horizon(),CommonMetrics.calculate(pairs))));
        return new Pagina<>(offset>=groups.size()?List.of():List.copyOf(groups.subList(offset,Math.min(offset+size,groups.size()))),page,size,groups.size(),pages(groups.size(),size));
    }
    public record Suggestion(String loteHistoricoId,String tipoSerie,String primeiroCorte,String ultimoCorte,int horizonteMeses,int janelaMediaMovel,int historicoEtsMeses,int numeroCortes,int produtos,long totalCelulas) {}
    public Suggestion suggestion(long lotId,String type,int horizon) {
        var lot=lots.get(lotId);
        if(!lot.tipo().equals("HISTORICO") || !lot.estado().equals("VALIDADO")) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Seleciona um histórico validado."); }
        if(!Set.of("VENDAS","CONSUMO").contains(type) || horizon<1 || horizon>12) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Série ou horizonte inválidos."); }
        var range=jdbc.queryForMap("SELECT MIN(periodo) primeiro,MAX(periodo) ultimo,COUNT(DISTINCT produto_id) produtos FROM observacao WHERE lote_id=? AND tipo=?",lotId,type);
        int count=((Number)range.get("produtos")).intValue();
        if(count<1 || count>1000) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"A série exige entre 1 e 1000 produtos."); }
        YearMonth min=YearMonth.from(((java.sql.Date)range.get("primeiro")).toLocalDate()).plusMonths(5);
        YearMonth max=YearMonth.from(((java.sql.Date)range.get("ultimo")).toLocalDate()).minusMonths(horizon);
        if(min.isAfter(max)) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Histórico curto para sugerir cortes com ETS e realizados completos."); }
        int folds=Math.min(24,20000/(count*horizon));
        if(max.minusMonths(folds-1L).isAfter(min)) { min=max.minusMonths(folds-1L); }
        int origins=(int)ChronoUnit.MONTHS.between(min,max)+1;
        return new Suggestion(Long.toString(lotId),type,min.toString(),max.toString(),horizon,3,24,origins,count,(long)origins*count*horizon);
    }
    private static String month(ResultSet rs,String field)throws SQLException { return rs.getDate(field).toLocalDate().toString().substring(0,7); }
    private static String decimal(BigDecimal value) { return value==null?null:value.setScale(4).toPlainString(); }
    private static BigDecimal number(String value) { return value==null?null:new BigDecimal(value); }
    private static int pages(long total,int size) { return (int)((total+size-1)/size); }
}
