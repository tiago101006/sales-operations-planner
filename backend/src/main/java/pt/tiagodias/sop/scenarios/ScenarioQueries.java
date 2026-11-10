package pt.tiagodias.sop.scenarios;

import java.math.*;
import java.sql.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.imports.ImportQueries;
import tools.jackson.databind.json.JsonMapper;
import static pt.tiagodias.sop.scenarios.ScenarioDtos.*;

@Repository
@Transactional(readOnly=true)
public class ScenarioQueries {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final JsonMapper json=JsonMapper.builder().build();
    public ScenarioQueries(JdbcTemplate jdbc) { this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc); }
    private static final String CENARIO="SELECT c.*,(SELECT COUNT(*) FROM revisao_cenario r WHERE r.cenario_id=c.id) revisoes,(SELECT r.id FROM revisao_cenario r WHERE r.cenario_id=c.id ORDER BY r.numero DESC LIMIT 1) ultima FROM cenario c ";
    private static final String REVISAO="SELECT r.*,(SELECT COUNT(*) FROM linha_cenario l WHERE l.revisao_id=r.id) linhas,(SELECT COUNT(*) FROM ajuste a WHERE a.revisao_id=r.id) ajustes FROM revisao_cenario r ";
    private static final String LINHAS="SELECT l.*,p.codigo,p.unidade,b.quantidade_base FROM linha_cenario l JOIN produto p ON p.id=l.produto_id JOIN linha_previsao b ON b.execucao_id=l.execucao_id AND b.produto_id=l.produto_id AND b.periodo=l.periodo ";
    private static final String AJUSTES="SELECT a.*,e.autor_id,u.nome autor_nome,e.ocorrido_em FROM ajuste a JOIN evento_revisao e ON e.id=a.evento_id JOIN utilizador u ON u.id=e.autor_id ";
    private static final String CHAIN="""
            WITH RECURSIVE cadeia AS (
              SELECT r.id,r.revisao_origem_id,r.pressupostos,0 nivel,CAST(9223372036854775807 AS SIGNED) limite
              FROM revisao_cenario r WHERE r.id=?
              UNION ALL
              SELECT r.id,r.revisao_origem_id,r.pressupostos,c.nivel+1,
                LEAST(c.limite,CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.pressupostos,'$.ajusteOrigemAteId')),'0') AS UNSIGNED))
              FROM revisao_cenario r JOIN cadeia c ON r.id=c.revisao_origem_id
              WHERE ? AND JSON_UNQUOTE(JSON_EXTRACT(c.pressupostos,'$.herdaAjustes'))='true'
            )
            """;

    private CenarioDto mapCenario(ResultSet rs,int n)throws SQLException {
        return new CenarioDto(rs.getString("id"),rs.getString("execucao_id"),rs.getString("nome"),rs.getString("tipo"),rs.getString("criado_por"),ImportQueries.instant(rs,"criado_em"),rs.getInt("revisoes"),rs.getString("ultima"));
    }
    private RevisaoDto mapRevisao(ResultSet rs,int n)throws SQLException {
        return new RevisaoDto(rs.getString("id"),rs.getString("cenario_id"),rs.getString("execucao_id"),rs.getInt("numero"),rs.getString("revisao_origem_id"),
                rs.getBigDecimal("fator").setScale(6).toPlainString(),json.readTree(rs.getString("pressupostos")),rs.getString("estado"),rs.getString("criado_por"),ImportQueries.instant(rs,"criado_em"),rs.getLong("versao_lock"),rs.getInt("linhas"),rs.getInt("ajustes"));
    }
    public CenarioDto cenario(long id) { return jdbc.query(CENARIO+"WHERE c.id=?",this::mapCenario,id).stream().findFirst().orElseThrow(()->missing("Cenário")); }
    public RevisaoDto revisao(long id) { return jdbc.query(REVISAO+"WHERE r.id=?",this::mapRevisao,id).stream().findFirst().orElseThrow(()->missing("Revisão")); }
    public Pagina<CenarioDto> cenarios(Long execution,int page,int size) {
        int offset=ImportQueries.offset(page,size);String where=execution==null?"":"WHERE c.execucao_id=? ";
        Object[] args=execution==null?new Object[]{}:new Object[]{execution};
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM cenario c "+where,Long.class,args);
        var values=new ArrayList<>(Arrays.asList(args));values.add(size);values.add(offset);
        return new Pagina<>(jdbc.query(CENARIO+where+"ORDER BY c.id DESC LIMIT ? OFFSET ?",this::mapCenario,values.toArray()),page,size,count,pages(count,size));
    }
    public Pagina<RevisaoDto> revisoes(long scenario,int page,int size) {
        int offset=ImportQueries.offset(page,size);cenario(scenario);
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM revisao_cenario WHERE cenario_id=?",Long.class,scenario);
        return new Pagina<>(jdbc.query(REVISAO+"WHERE r.cenario_id=? ORDER BY r.numero DESC LIMIT ? OFFSET ?",this::mapRevisao,scenario,size,offset),page,size,count,pages(count,size));
    }
    private LinhaDto line(ResultSet rs,int n)throws SQLException {
        var base=rs.getBigDecimal("quantidade_base");var value=rs.getBigDecimal("quantidade_final");
        return new LinhaDto(rs.getString("produto_id"),rs.getString("codigo"),rs.getString("unidade"),month(rs,"periodo"),decimal(base),decimal(rs.getBigDecimal("quantidade_calculada")),decimal(value),decimal(value.subtract(base)),percent(base,value));
    }
    public Pagina<LinhaDto> linhas(long revision,int page,int size) {
        int offset=ImportQueries.offset(page,size);long count=revisao(revision).totalLinhas();
        return new Pagina<>(jdbc.query(LINHAS+"WHERE l.revisao_id=? ORDER BY p.codigo,l.periodo LIMIT ? OFFSET ?",this::line,revision,size,offset),page,size,count,pages(count,size));
    }
    private AjusteDto ajuste(ResultSet rs,long context)throws SQLException {
        return new AjusteDto(rs.getString("id"),rs.getString("evento_id"),rs.getString("revisao_id"),rs.getString("produto_id"),month(rs,"periodo"),rs.getInt("sequencia"),
                decimal(rs.getBigDecimal("valor_anterior")),decimal(rs.getBigDecimal("valor_novo")),rs.getString("justificacao"),rs.getString("autor_id"),rs.getString("autor_nome"),ImportQueries.instant(rs,"ocorrido_em"),rs.getLong("revisao_id")!=context);
    }
    public AjusteDto ajuste(long id,long context) { return jdbc.query(AJUSTES+"WHERE a.id=?",(rs,n)->ajuste(rs,context),id).stream().findFirst().orElseThrow(()->missing("Ajuste")); }
    public Pagina<AjusteDto> ajustes(long revision,boolean inherited,int page,int size) {
        int offset=ImportQueries.offset(page,size);revisao(revision);
        String filter=" JOIN cadeia c ON c.id=a.revisao_id WHERE a.id<=c.limite ";
        long count=jdbc.queryForObject(CHAIN+"SELECT COUNT(*) FROM ajuste a"+filter,Long.class,revision,inherited);
        return new Pagina<>(jdbc.query(CHAIN+AJUSTES+filter+"ORDER BY c.nivel DESC,a.id LIMIT ? OFFSET ?",(rs,n)->ajuste(rs,revision),revision,inherited,size,offset),page,size,count,pages(count,size));
    }
    public Pagina<EventoDto> eventos(long revision,int page,int size) {
        int offset=ImportQueries.offset(page,size);revisao(revision);
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM evento_revisao WHERE revisao_id=?",Long.class,revision);
        return new Pagina<>(jdbc.query("SELECT e.*,u.nome autor_nome FROM evento_revisao e JOIN utilizador u ON u.id=e.autor_id WHERE e.revisao_id=? ORDER BY e.id LIMIT ? OFFSET ?",
                (rs,n)->new EventoDto(rs.getString("id"),rs.getString("revisao_id"),rs.getString("tipo"),rs.getString("autor_id"),rs.getString("autor_nome"),ImportQueries.instant(rs,"ocorrido_em"),rs.getString("motivo")),revision,size,offset),page,size,count,pages(count,size));
    }
    private record Product(long id,String code,String unit) {}
    public ComparacaoDto comparar(List<Long> revisions,int page,int size) {
        int offset=ImportQueries.offset(page,size);
        if(revisions.size()<2 || revisions.size()>5 || new HashSet<>(revisions).size()!=revisions.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Seleciona 2 a 5 revisões distintas.");
        }
        var headers=revisions.stream().map(this::revisao).toList();String execution=headers.get(0).execucaoId();
        if(headers.stream().anyMatch(r->!r.execucaoId().equals(execution))) { throw new ResponseStatusException(HttpStatus.CONFLICT,"A comparação exige revisões da mesma execução."); }
        long count=jdbc.queryForObject("SELECT COUNT(DISTINCT produto_id) FROM linha_cenario WHERE revisao_id=?",Long.class,revisions.get(0));
        var products=jdbc.query("SELECT DISTINCT p.id,p.codigo,p.unidade FROM linha_cenario l JOIN produto p ON p.id=l.produto_id WHERE l.revisao_id=? ORDER BY p.codigo,p.id LIMIT ? OFFSET ?",
                (rs,n)->new Product(rs.getLong("id"),rs.getString("codigo"),rs.getString("unidade")),revisions.get(0),size,offset);
        if(products.isEmpty()) { return new ComparacaoDto(execution,headers,new Pagina<>(List.of(),page,size,count,pages(count,size))); }
        var data=named.query(LINHAS+"WHERE l.revisao_id IN (:revisoes) AND l.produto_id IN (:produtos) ORDER BY p.codigo,l.periodo,l.revisao_id",
                Map.of("revisoes",revisions,"produtos",products.stream().map(Product::id).toList()),(rs,n)->Map.entry(rs.getLong("revisao_id"),line(rs,n)));
        var output=new ArrayList<ProdutoComparado>();
        for(var product:products) {
            var byMonth=new TreeMap<String,List<Map.Entry<Long,LinhaDto>>>();
            for(var row:data) { if(row.getValue().produtoId().equals(Long.toString(product.id()))) { byMonth.computeIfAbsent(row.getValue().periodo(),k->new ArrayList<>()).add(row); } }
            var periods=new ArrayList<PeriodoComparado>();
            byMonth.forEach((month,rows)->{
                var values=new ArrayList<ValorComparado>();
                for(long revision:revisions) {
                    var row=rows.stream().filter(e->e.getKey()==revision).findFirst().orElseThrow().getValue();
                    values.add(new ValorComparado(Long.toString(revision),row.quantidadeCalculada(),row.quantidadeFinal(),row.desvioAbsoluto(),row.desvioPercentual()));
                }
                periods.add(new PeriodoComparado(month,rows.get(0).getValue().quantidadeBase(),List.copyOf(values)));
            });
            output.add(new ProdutoComparado(Long.toString(product.id()),product.code(),product.unit(),List.copyOf(periods)));
        }
        return new ComparacaoDto(execution,headers,new Pagina<>(List.copyOf(output),page,size,count,pages(count,size)));
    }
    private static String month(ResultSet rs,String column)throws SQLException { return rs.getDate(column).toLocalDate().toString().substring(0,7); }
    private static String decimal(BigDecimal number) { return number.setScale(4,RoundingMode.HALF_UP).toPlainString(); }
    private static String percent(BigDecimal base,BigDecimal value) { return base.signum()==0?null:value.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base,4,RoundingMode.HALF_UP).toPlainString(); }
    private static int pages(long count,int size) { return (int)((count+size-1)/size); }
    private static ResponseStatusException missing(String name) { return new ResponseStatusException(HttpStatus.NOT_FOUND,name+" não encontrado(a)."); }
}
