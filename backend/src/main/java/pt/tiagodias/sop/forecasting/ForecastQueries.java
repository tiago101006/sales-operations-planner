package pt.tiagodias.sop.forecasting;

import pt.tiagodias.sop.shared.SearchPage;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.imports.ImportQueries;
import static pt.tiagodias.sop.forecasting.ForecastDtos.*;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ForecastQueries {
    private static final String HEADER = """
            SELECT e.*,
             (SELECT COUNT(*) FROM execucao_produto p WHERE p.execucao_id=e.id AND p.estado IN ('PREVISTO','RECURSO')) previstos,
             (SELECT COUNT(*) FROM execucao_produto p WHERE p.execucao_id=e.id AND p.estado='RECURSO') recursos,
             (SELECT COUNT(*) FROM execucao_produto p WHERE p.execucao_id=e.id AND p.estado='ERRO') erros,
             (SELECT COUNT(*) FROM execucao_produto p WHERE p.execucao_id=e.id AND p.estado='INSUFICIENTE') insuficientes,
             (SELECT COUNT(*) FROM linha_previsao p WHERE p.execucao_id=e.id) total_linhas
            FROM execucao_previsao e
            """;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public ForecastQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private ExecucaoDto map(ResultSet rs, int row) throws SQLException {
        return new ExecucaoDto(rs.getString("id"), rs.getString("lote_historico_id"), rs.getString("tipo_serie"),
                ImportQueries.instant(rs, "corte_temporal"), rs.getDate("ultimo_periodo_treino").toLocalDate().toString().substring(0, 7),
                rs.getInt("horizonte_meses"), rs.getString("metodo_pedido"), json.readTree(rs.getString("parametros")),
                rs.getString("versao_software"), rs.getString("versao_motor"), rs.getString("estado"), rs.getString("criado_por"),
                ImportQueries.instant(rs, "criado_em"), ImportQueries.instant(rs, "concluido_em"), rs.getInt("previstos"), rs.getInt("insuficientes"), rs.getInt("total_linhas"), rs.getInt("recursos"), rs.getInt("erros"));
    }

    public ExecucaoDto get(long id) {
        return jdbc.query(HEADER + " WHERE e.id=?", this::map, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execução não encontrada."));
    }

    public Pagina<ExecucaoDto> list(int page, int size) {

        var result=SearchPage.page(jdbc,HEADER,"id DESC",this::map,page,size,"id,metodo_pedido,estado,tipo_serie,lote_historico_id","criado_em");
        return new Pagina<>(result.items(),page,size,result.total(),result.pages());
    }

    public Pagina<LinhaDto> lines(long id, int page, int size) {
        YearMonth last=YearMonth.parse(get(id).ultimoPeriodoTreino());
        var result=SearchPage.page(jdbc,"SELECT l.*,p.codigo,p.nome nome_produto,p.unidade FROM linha_previsao l JOIN produto p ON p.id=l.produto_id WHERE l.execucao_id=?","codigo,periodo",(rs,n)->new LinhaDto(rs.getString("produto_id"),rs.getString("codigo"),rs.getString("unidade"),rs.getDate("periodo").toLocalDate().toString().substring(0,7),(int)ChronoUnit.MONTHS.between(last,YearMonth.from(rs.getDate("periodo").toLocalDate())),rs.getBigDecimal("quantidade_base").setScale(4).toPlainString()),page,size,"codigo,nome_produto","periodo",id);
        return new Pagina<>(result.items(),page,size,result.total(),result.pages());
    }

    public Pagina<DiagnosticoDto> diagnostics(long id, int page, int size) {
        get(id);
        var result=SearchPage.page(jdbc,"SELECT ep.*,p.codigo,p.nome nome_produto FROM execucao_produto ep JOIN produto p ON p.id=ep.produto_id WHERE ep.execucao_id=?","codigo",(rs,n)->new DiagnosticoDto(rs.getString("produto_id"),rs.getString("codigo"),rs.getString("estado"),rs.getString("metodo_aplicado"),rs.getString("diagnostico"),json.readTree(rs.getString("parametros_aplicados"))),page,size,"codigo,nome_produto,estado,metodo_aplicado,diagnostico",null,id);
        return new Pagina<>(result.items(),page,size,result.total(),result.pages());
    }
    private static int pages(long total, int size) { return (int) ((total + size - 1) / size); }
}
