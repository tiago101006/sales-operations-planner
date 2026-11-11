package pt.tiagodias.sop.monitoring;

import pt.tiagodias.sop.shared.SearchPage;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.imports.ImportQueries;
import pt.tiagodias.sop.scenarios.ScenarioDtos.Pagina;
import static pt.tiagodias.sop.monitoring.MonitoringDtos.*;
import static pt.tiagodias.sop.monitoring.MonitoringMetrics.*;

@Repository
@Transactional(readOnly=true)
public class MonitoringQueries {
    private final JdbcTemplate jdbc;private final NamedParameterJdbcTemplate named;
    public MonitoringQueries(JdbcTemplate jdbc) { this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc); }
    private static final String HEADER="SELECT a.*,(SELECT COUNT(*) FROM avaliacao_celula c WHERE c.avaliacao_id=a.id) celulas FROM avaliacao a ";
    private static final String CELLS="""
            SELECT c.*,produto_atual.nome nome_produto,p.codigo_produto,p.unidade,p.quantidade_base,p.quantidade_aprovada,e.ultimo_periodo_treino,
            o.quantidade realizado,o.disponivel_em realizado_disponivel,a.corte_realizados,a.lote_operacional_id,
            op.id operacional_id,op.disponivel_em operacional_disponivel,
            op.unidades_solicitadas,op.unidades_satisfeitas_no_prazo,op.dias_observados_com_procura,op.dias_com_procura_sem_stock,
            op.stock_fecho,op.limite_stock,op.unidades_perdidas,op.prazo_medio_dias
            FROM avaliacao_celula c JOIN avaliacao a ON a.id=c.avaliacao_id
            JOIN linha_plano p ON p.plano_id=c.plano_id AND p.produto_id=c.produto_id AND p.periodo=c.periodo
            JOIN produto produto_atual ON produto_atual.id=c.produto_id
            JOIN plano_publicado pp ON pp.id=p.plano_id JOIN revisao_cenario r ON r.id=pp.revisao_id
            JOIN execucao_previsao e ON e.id=r.execucao_id
            LEFT JOIN observacao o ON o.id=c.observacao_id
            LEFT JOIN resultado_operacional op ON op.lote_id=a.lote_operacional_id AND op.produto_id=c.produto_id AND op.periodo=c.periodo
            """;
    private Avaliacao mapHeader(ResultSet rs,int n)throws SQLException {
        return new Avaliacao(rs.getString("id"),rs.getString("plano_id"),rs.getString("lote_realizados_id"),rs.getString("lote_operacional_id"),month(rs,"periodo_inicio"),month(rs,"periodo_fim"),ImportQueries.instant(rs,"corte_realizados"),rs.getString("versao_calculo"),rs.getString("criado_por"),ImportQueries.instant(rs,"criado_em"),rs.getLong("celulas"));
    }
    public Avaliacao get(long id) { return jdbc.query(HEADER+"WHERE a.id=?",this::mapHeader,id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Avaliação não encontrada.")); }
    public Pagina<Avaliacao> list(Long plan,int page,int size) {
        String filter=plan==null?"":"WHERE a.plano_id=? ";Object[] args=plan==null?new Object[0]:new Object[]{plan};
        var result=SearchPage.page(jdbc,HEADER+filter,"id DESC",this::mapHeader,page,size,"id,plano_id,lote_realizados_id","criado_em",args);
        return new Pagina<>(result.items(),page,size,result.total(),result.pages());
    }
    private Celula mapCell(ResultSet rs,int n)throws SQLException {
        Instant cut=ImportQueries.instant(rs,"corte_realizados"),available=ImportQueries.instant(rs,"realizado_disponivel");
        boolean temporal=rs.getBoolean("elegivel_temporal");String reason=rs.getString("motivo_exclusao");
        boolean known=available!=null && !available.isAfter(cut);
        String opReason=!temporal?reason:rs.getString("lote_operacional_id")==null?"SEM_LOTE_OPERACIONAL":rs.getString("operacional_id")==null?"SEM_REGISTO_OPERACIONAL":ImportQueries.instant(rs,"operacional_disponivel").isAfter(cut)?"OPERACIONAL_APOS_CORTE":null;
        Operacional op=null;
        if(opReason==null) {
            var requested=rs.getBigDecimal("unidades_solicitadas");var satisfied=rs.getBigDecimal("unidades_satisfeitas_no_prazo");
            Integer days=rs.getObject("dias_observados_com_procura",Integer.class),out=rs.getObject("dias_com_procura_sem_stock",Integer.class);
            var stock=rs.getBigDecimal("stock_fecho");var threshold=rs.getBigDecimal("limite_stock");
            op=new Operacional(rs.getString("operacional_id"),ImportQueries.instant(rs,"operacional_disponivel"),decimal(requested),decimal(satisfied),days,out,decimal(stock),decimal(threshold),decimal(rs.getBigDecimal("unidades_perdidas")),decimal(rs.getBigDecimal("prazo_medio_dias")),ratio(satisfied,requested),days==null || out==null?null:ratio(java.math.BigDecimal.valueOf(out),java.math.BigDecimal.valueOf(days)),stock==null || threshold==null?null:decimal(stock.subtract(threshold).max(java.math.BigDecimal.ZERO)));
        }
        var period=rs.getDate("periodo").toLocalDate();int horizon=(int)ChronoUnit.MONTHS.between(YearMonth.from(rs.getDate("ultimo_periodo_treino").toLocalDate()),YearMonth.from(period));
        return new Celula(rs.getString("produto_id"),rs.getString("codigo_produto"),rs.getString("unidade"),month(rs,"periodo"),horizon,decimal(rs.getBigDecimal("quantidade_base")),decimal(rs.getBigDecimal("quantidade_aprovada")),rs.getString("observacao_id"),known?decimal(rs.getBigDecimal("realizado")):null,available,temporal,reason==null,reason,op,opReason);
    }
    public Pagina<Celula> cells(long id,int page,int size) {
        get(id);
        var result=SearchPage.page(jdbc,CELLS+"WHERE c.avaliacao_id=?","codigo_produto,periodo",this::mapCell,page,size,"codigo_produto,nome_produto,motivo_exclusao","periodo",id);
        return new Pagina<>(result.items(),page,size,result.total(),result.pages());
    }
    private record Groups(List<List<Celula>> cells,long count) {}
    private Groups groups(long id,int page,int size) {
        int offset=ImportQueries.offset(page,size);get(id);
        long count=jdbc.queryForObject("SELECT COUNT(DISTINCT produto_id) FROM avaliacao_celula WHERE avaliacao_id=?",Long.class,id);
        var products=jdbc.queryForList("SELECT DISTINCT c.produto_id,p.codigo_produto FROM avaliacao_celula c JOIN linha_plano p ON p.plano_id=c.plano_id AND p.produto_id=c.produto_id AND p.periodo=c.periodo WHERE c.avaliacao_id=? ORDER BY p.codigo_produto LIMIT ? OFFSET ?",id,size,offset);
        if(products.isEmpty()) { return new Groups(List.of(),count); }
        var ids=products.stream().map(p->((Number)p.get("produto_id")).longValue()).toList();
        var rows=named.query(CELLS+"WHERE c.avaliacao_id=:id AND c.produto_id IN (:products) ORDER BY p.codigo_produto,c.periodo",Map.of("id",id,"products",ids),this::mapCell);
        var grouped=new LinkedHashMap<String,List<Celula>>();for(var row:rows) { grouped.computeIfAbsent(row.produtoId(),key->new ArrayList<>()).add(row); }
        return new Groups(List.copyOf(grouped.values()),count);
    }
    public Pagina<Precisao> precision(long id,int page,int size) { var groups=groups(id,page,size);return new Pagina<>(groups.cells().stream().map(MonitoringMetrics::precision).toList(),page,size,groups.count(),pages(groups.count(),size)); }
    public Pagina<Operacao> operation(long id,int page,int size) { var groups=groups(id,page,size);return new Pagina<>(groups.cells().stream().map(MonitoringMetrics::operation).toList(),page,size,groups.count(),pages(groups.count(),size)); }
    private static String month(ResultSet rs,String column)throws SQLException { return rs.getDate(column).toLocalDate().toString().substring(0,7); }
    private static int pages(long count,int size) { return (int)((count+size-1)/size); }
}
