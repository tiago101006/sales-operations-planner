package pt.tiagodias.sop.imports;

import java.nio.file.Path;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.catalog.*;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import static pt.tiagodias.sop.imports.ImportDtos.*;

@Service
public class OutcomeImportWriter {
    private final JdbcTemplate jdbc;private final ProdutoRepository products;private final UtilizadorRepository users;private final ImportQueries queries;
    public OutcomeImportWriter(JdbcTemplate jdbc,ProdutoRepository products,UtilizadorRepository users,ImportQueries queries) { this.jdbc=jdbc;this.products=products;this.users=users;this.queries=queries; }
    @Transactional
    public LoteDto persist(OutcomeCsvParser.Parsed parsed,String type,String origin,String filename,Path stored,String hash,Long previous,String email,Instant now) {
        if(previous!=null) {
            var old=queries.get(previous);
            if(!old.tipo().equals(type) || !old.estado().equals("VALIDADO")) { throw new ResponseStatusException(HttpStatus.CONFLICT,"A correção exige lote validado do mesmo tipo."); }
        }
        var errors=new ArrayList<>(parsed.errors());var catalog=new HashMap<String,Produto>();
        var codes=parsed.rows().stream().map(OutcomeCsvParser.Row::product).distinct().sorted().toList();
        for(int i=0;i<codes.size();i+=500) { for(var product:products.findForImport(codes.subList(i,Math.min(i+500,codes.size())))) { catalog.put(product.getCodigo(),product); } }
        for(var row:parsed.rows()) {
            var product=catalog.get(row.product());
            if(product==null) { erro(errors,row.line(),"produto","PRODUTO_DESCONHECIDO","O produto não existe no catálogo."); }
            else if(!product.getUnidade().equals(row.unit())) { erro(errors,row.line(),"unidade","UNIDADE_INCOMPATIVEL","A unidade difere da unidade de planeamento."); }
        }
        long author=users.findByEmail(email).orElseThrow().getId();var key=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var ps=connection.prepareStatement("INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,substitui_lote_id,importado_por,importado_em,disponivel_em,total_linhas) VALUES (?,'VALIDANDO',?,?,?,?, 'v1',?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            ps.setString(1,type);ps.setString(2,origin);ps.setString(3,filename);ps.setString(4,stored.toString());ps.setString(5,hash);ps.setObject(6,previous);ps.setLong(7,author);ps.setObject(8,utc(now));ps.setObject(9,utc(now));ps.setInt(10,parsed.count());return ps;
        },key);
        long lot=Objects.requireNonNull(key.getKey()).longValue();
        if(!errors.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO erro_importacao(lote_id,numero_linha,campo,codigo,mensagem) VALUES (?,?,?,?,?)",errors,500,(ps,e)->{
                ps.setLong(1,lot);ps.setLong(2,e.numeroLinha());ps.setString(3,e.campo());ps.setString(4,e.codigo());ps.setString(5,e.mensagem());
            });jdbc.update("UPDATE lote SET estado='REJEITADO' WHERE id=?",lot);
        } else {
            if(type.equals("REALIZADOS")) {
                jdbc.batchUpdate("INSERT INTO observacao(lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (?,?,?,'PROCURA_REALIZADA',?,?)",parsed.rows(),500,(ps,row)->{
                    ps.setLong(1,lot);ps.setLong(2,catalog.get(row.product()).getId());ps.setObject(3,row.month().atDay(1));ps.setBigDecimal(4,row.quantity());ps.setObject(5,utc(row.available()));
                });
            } else {
                jdbc.batchUpdate("INSERT INTO resultado_operacional(lote_id,produto_id,periodo,disponivel_em,unidades_solicitadas,unidades_satisfeitas_no_prazo,dias_observados_com_procura,dias_com_procura_sem_stock,stock_fecho,limite_stock,unidades_perdidas,prazo_medio_dias) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",parsed.rows(),500,(ps,row)->{
                    ps.setLong(1,lot);ps.setLong(2,catalog.get(row.product()).getId());ps.setObject(3,row.month().atDay(1));ps.setObject(4,utc(row.available()));
                    for(int i=0;i<OutcomeCsvParser.FIELDS.size();i++) { ps.setObject(i+5,row.values().get(OutcomeCsvParser.FIELDS.get(i))); }
                });
            }
            jdbc.update("UPDATE lote SET estado='VALIDADO',validado_em=? WHERE id=?",utc(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)),lot);
        }
        return queries.get(lot);
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
}
