package pt.tiagodias.sop.imports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import static pt.tiagodias.sop.imports.ImportDtos.*;

@Repository
public class ImportQueries {
    private static final String SELECT_LOTE = "SELECT l.*,(SELECT COUNT(*) FROM erro_importacao e WHERE e.lote_id=l.id) total_erros FROM lote l ";
    private final JdbcTemplate jdbc;
    public ImportQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private LoteDto map(ResultSet rs, int row) throws SQLException {
        Long previous = rs.getObject("substitui_lote_id", Long.class);
        return new LoteDto(rs.getString("id"), rs.getString("tipo"), rs.getString("estado"), rs.getString("origem"),
                rs.getString("nome_ficheiro"), rs.getString("sha256"), rs.getString("versao_esquema"),
                previous == null ? null : previous.toString(), rs.getString("importado_por"), instant(rs, "importado_em"),
                instant(rs, "disponivel_em"), instant(rs, "validado_em"), rs.getInt("total_linhas"), rs.getInt("total_erros"));
    }

    public static int offset(int page, int size) {
        if (page < 0 || size < 1 || size > 200 || ((long) page * size) > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Usa page >= 0 e size entre 1 e 200, sem exceder o limite de paginação.");
        }
        return page * size;
    }

    public LoteDto get(long id) {
        return jdbc.query(SELECT_LOTE + "WHERE l.id=?", this::map, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lote não encontrado."));
    }

    public Pagina<LoteDto> list(int page, int size) {
        int offset = offset(page, size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM lote", Long.class);
        return new Pagina<>(jdbc.query(SELECT_LOTE + "ORDER BY l.id DESC LIMIT ? OFFSET ?", this::map, size, offset), page, size, total, pages(total, size));
    }

    public Pagina<Diagnostico> errors(long id, int page, int size) {
        int offset = offset(page, size);
        get(id);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM erro_importacao WHERE lote_id=?", Long.class, id);
        return new Pagina<>(jdbc.query("SELECT * FROM erro_importacao WHERE lote_id=? ORDER BY id LIMIT ? OFFSET ?",
                (rs, n) -> new Diagnostico(rs.getLong("numero_linha"), rs.getString("campo"), rs.getString("codigo"), rs.getString("mensagem")),
                id, size, offset), page, size, total, pages(total, size));
    }

    public Pagina<ObservacaoDto> observations(long id, int page, int size) {
        int offset = offset(page, size);
        var lot=get(id);
        if (!lot.estado().equals("VALIDADO") || !(lot.tipo().equals("HISTORICO") || lot.tipo().equals("REALIZADOS"))) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Consulta observações de HISTORICO/REALIZADOS validado."); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM observacao WHERE lote_id=?", Long.class, id);
        return new Pagina<>(jdbc.query("SELECT o.*,p.codigo,p.unidade FROM observacao o JOIN produto p ON p.id=o.produto_id WHERE o.lote_id=? ORDER BY p.codigo,o.periodo,o.tipo LIMIT ? OFFSET ?",
                (rs, n) -> new ObservacaoDto(rs.getString("id"), rs.getString("produto_id"), rs.getString("codigo"),
                        rs.getDate("periodo").toLocalDate().toString().substring(0, 7), rs.getString("tipo"),
                        rs.getBigDecimal("quantidade").setScale(4).toPlainString(), rs.getString("unidade"), instant(rs, "disponivel_em")),
                id, size, offset), page, size, total, pages(total, size));
    }
    public Pagina<ResultadoDto> operational(long id,int page,int size) {
        int offset=offset(page,size);var lot=get(id);
        if(!lot.estado().equals("VALIDADO") || !lot.tipo().equals("OPERACIONAL")) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Consulta resultados de um lote OPERACIONAL validado."); }
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM resultado_operacional WHERE lote_id=?",Long.class,id);
        var rows=jdbc.query("SELECT o.*,p.codigo,p.unidade FROM resultado_operacional o JOIN produto p ON p.id=o.produto_id WHERE o.lote_id=? ORDER BY p.codigo,o.periodo LIMIT ? OFFSET ?",(rs,n)->{
            var values=new java.util.LinkedHashMap<String,String>();
            for(String field:OutcomeCsvParser.FIELDS) { var value=rs.getBigDecimal(field);values.put(field,value==null?null:value.toPlainString()); }
            return new ResultadoDto(rs.getString("id"),rs.getString("produto_id"),rs.getString("codigo"),rs.getDate("periodo").toLocalDate().toString().substring(0,7),rs.getString("unidade"),instant(rs,"disponivel_em"),values);
        },id,size,offset);
        return new Pagina<>(rows,page,size,count,pages(count,size));
    }

    private static int pages(long total, int size) { return (int) ((total + size - 1) / size); }
}
