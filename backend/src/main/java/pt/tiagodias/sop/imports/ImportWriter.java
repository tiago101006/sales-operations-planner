package pt.tiagodias.sop.imports;

import java.nio.file.Path;
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
import pt.tiagodias.sop.catalog.*;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import static pt.tiagodias.sop.imports.ImportDtos.*;

@Service
public class ImportWriter {
    private final JdbcTemplate jdbc;
    private final ProdutoRepository products;
    private final UtilizadorRepository users;
    private final ImportQueries queries;

    public ImportWriter(JdbcTemplate jdbc, ProdutoRepository products, UtilizadorRepository users, ImportQueries queries) {
        this.jdbc = jdbc; this.products = products; this.users = users; this.queries = queries;
    }

    @Transactional
    public LoteDto persist(ParsedCsv parsed, String origem, String filename, Path stored, String hash,
                           Long previousId, String email, Instant receivedAt) {
        long author = users.findByEmail(email).orElseThrow().getId();
        if (previousId != null) {
            var previous = queries.get(previousId);
            if (!previous.tipo().equals("HISTORICO") || !previous.estado().equals("VALIDADO")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A correção deve referenciar um histórico validado.");
            }
        }
        var errors = new ArrayList<>(parsed.erros());
        var codes = parsed.linhas().stream().map(Linha::produto).distinct().sorted().toList();
        var catalog = new HashMap<String, Produto>();
        for (int start = 0; start < codes.size(); start += 500) {
            for (var product : products.findForImport(codes.subList(start, Math.min(start + 500, codes.size())))) {
                catalog.put(product.getCodigo(), product);
            }
        }
        for (var row : parsed.linhas()) {
            var product = catalog.get(row.produto());
            if (product == null) { erro(errors, row.numeroLinha(), "produto", "PRODUTO_DESCONHECIDO", "O produto não existe no catálogo."); }
            else if (!product.isAtivo()) { erro(errors, row.numeroLinha(), "produto", "PRODUTO_INATIVO", "O produto está inativo."); }
            else if (!product.getUnidade().equals(row.unidade())) {
                erro(errors, row.numeroLinha(), "unidade", "UNIDADE_INCOMPATIVEL", "A unidade difere da unidade de planeamento do produto.");
            }
        }
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO lote(tipo,estado,origem,nome_ficheiro,localizacao_ficheiro,sha256,versao_esquema,
                                     substitui_lote_id,importado_por,importado_em,disponivel_em,total_linhas)
                    VALUES ('HISTORICO','VALIDANDO',?,?,?,?, 'v1',?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, origem); statement.setString(2, filename);
            statement.setString(3, stored.toString()); statement.setString(4, hash);
            statement.setObject(5, previousId); statement.setLong(6, author);
            statement.setObject(7, utc(receivedAt)); statement.setObject(8, utc(receivedAt));
            statement.setInt(9, parsed.totalLinhas());
            return statement;
        }, key);
        long lotId = Objects.requireNonNull(key.getKey()).longValue();
        if (!errors.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO erro_importacao(lote_id,numero_linha,campo,codigo,mensagem) VALUES (?,?,?,?,?)",
                    errors, 500, (ps, e) -> {
                        ps.setLong(1, lotId); ps.setLong(2, e.numeroLinha()); ps.setString(3, e.campo());
                        ps.setString(4, e.codigo()); ps.setString(5, e.mensagem());
                    });
            jdbc.update("UPDATE lote SET estado='REJEITADO' WHERE id=?", lotId);
        } else {
            jdbc.batchUpdate("INSERT INTO observacao(lote_id,produto_id,periodo,tipo,quantidade,disponivel_em) VALUES (?,?,?,?,?,?)",
                    parsed.linhas(), 500, (ps, row) -> {
                        ps.setLong(1, lotId); ps.setLong(2, catalog.get(row.produto()).getId());
                        ps.setObject(3, row.periodo().atDay(1)); ps.setString(4, row.tipo().name());
                        ps.setBigDecimal(5, row.quantidade()); ps.setObject(6, utc(row.disponivelEm()));
                    });
            jdbc.update("UPDATE lote SET estado='VALIDADO',validado_em=? WHERE id=?", utc(Instant.now().truncatedTo(ChronoUnit.MICROS)), lotId);
        }
        return queries.get(lotId);
    }

    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
