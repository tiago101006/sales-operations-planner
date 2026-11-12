package pt.tiagodias.sop.planning;

import pt.tiagodias.sop.shared.SearchPage;
import java.io.*;
import java.sql.*;
import org.apache.commons.csv.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.imports.ImportQueries;
import pt.tiagodias.sop.scenarios.ScenarioDtos.Pagina;
import static pt.tiagodias.sop.planning.PlanDtos.*;

@Repository
@Transactional(readOnly=true)
public class PlanQueries {
    private static final String FROM="""
            FROM plano_publicado p
            JOIN revisao_cenario r ON r.id=p.revisao_id AND r.estado='APROVADA'
            JOIN evento_revisao e ON e.id=p.evento_aprovacao_id AND e.revisao_id=p.revisao_id AND e.tipo='APROVACAO'
            """;
    private static final String HEADER="""
            SELECT p.*,r.cenario_id,r.execucao_id,e.autor_id,e.ocorrido_em,
            (SELECT COUNT(*) FROM linha_plano l WHERE l.plano_id=p.id) total_linhas
            """+FROM;
    private static final String LINES="SELECT * FROM linha_plano WHERE plano_id=? ORDER BY codigo_produto,periodo,produto_id";
    private final JdbcTemplate jdbc;

    public PlanQueries(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    private PlanoDto mapPlan(ResultSet rs,int n)throws SQLException {
        return new PlanoDto(rs.getString("id"),rs.getString("revisao_id"),rs.getString("cenario_id"),rs.getString("execucao_id"),
                rs.getString("versao_contrato"),rs.getString("autor_id"),ImportQueries.instant(rs,"ocorrido_em"),
                ImportQueries.instant(rs,"publicado_em"),rs.getLong("total_linhas"));
    }
    private LinhaPlanoDto mapLine(ResultSet rs,int n)throws SQLException {
        return new LinhaPlanoDto(rs.getString("produto_id"),rs.getString("codigo_produto"),
                rs.getDate("periodo").toLocalDate().toString().substring(0,7),rs.getString("unidade"),
                rs.getBigDecimal("quantidade_base").setScale(4).toPlainString(),
                rs.getBigDecimal("quantidade_aprovada").setScale(4).toPlainString());
    }
    public PlanoDto get(long id) {
        return jdbc.query(HEADER+" WHERE p.id=?",this::mapPlan,id).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Plano publicado não encontrado."));
    }
    public Pagina<PlanoDto> list(int page,int size) {

        var result=SearchPage.page(jdbc,HEADER,"id DESC",this::mapPlan,page,size,"id,revisao_id,cenario_id,autor_id","publicado_em");
        return new Pagina<>(result.items(),page,size,result.total(),result.pages());
    }
    public Pagina<LinhaPlanoDto> lines(long id,int page,int size) {
        get(id);
        var result=SearchPage.page(jdbc,"SELECT l.*,p.nome nome_produto FROM linha_plano l JOIN produto p ON p.id=l.produto_id WHERE l.plano_id=?","codigo_produto,periodo,produto_id",this::mapLine,page,size,"codigo_produto,nome_produto","periodo",id);
        return new Pagina<>(result.items(),page,size,result.total(),result.pages());
    }
    public void export(long id,Writer writer)throws IOException {
        get(id);
        var format=CSVFormat.DEFAULT.builder().setDelimiter(';').setRecordSeparator("\r\n")
                .setHeader("plano_id","produto","periodo","unidade","quantidade_base","quantidade").get();
        try(var printer=new CSVPrinter(writer,format)) {
            // MySQL streaming: consume rows inside this transaction, without retaining the full plan.
            jdbc.query(connection->{
                var statement=connection.prepareStatement(LINES,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
                statement.setLong(1,id);statement.setFetchSize(Integer.MIN_VALUE);return statement;
            },(ResultSetExtractor<Void>) rows->{
                while(rows.next()) {
                    var row=mapLine(rows,0);
                    try { printer.printRecord(Long.toString(id),safeCsvText(row.produto()),row.periodo(),safeCsvText(row.unidade()),row.quantidadeBase(),row.quantidade()); }
                    catch(IOException exception) { throw new UncheckedIOException(exception); }
                }
                return null;
            });
        } catch(UncheckedIOException exception) { throw exception.getCause(); }
    }
    static String safeCsvText(String value) {
        int first = 0;
        while (first < value.length() && (Character.isWhitespace(value.charAt(first))
                || Character.isSpaceChar(value.charAt(first)) || Character.isISOControl(value.charAt(first))
                || value.charAt(first) == '\uFEFF')) { first++; }
        boolean formula = first < value.length() && "=+-@＝＋－＠".indexOf(value.charAt(first)) >= 0;
        boolean control = !value.isEmpty() && (Character.isISOControl(value.charAt(0)) || value.charAt(0) == '\uFEFF');
        return formula || control ? "'" + value : value;
    }
    private static int pages(long count,int size) { return (int)((count+size-1)/size); }
}
