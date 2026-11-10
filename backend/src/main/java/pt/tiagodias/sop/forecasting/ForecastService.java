package pt.tiagodias.sop.forecasting;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import pt.tiagodias.sop.imports.ImportQueries;
import static pt.tiagodias.sop.forecasting.ForecastDtos.*;

@Service
public class ForecastService {
    private final JdbcTemplate jdbc;
    private final ImportQueries lots;
    private final UtilizadorRepository users;
    private final ForecastQueries queries;
    private final ForecastEngine engine;
    private final String softwareVersion;

    public ForecastService(JdbcTemplate jdbc, ImportQueries lots, UtilizadorRepository users,
                           ForecastQueries queries, ForecastEngine engine,
                           @Value("${sop.software-version}") String softwareVersion) {
        this.jdbc = jdbc; this.lots = lots; this.users = users;
        this.queries = queries; this.engine = engine; this.softwareVersion = softwareVersion;
    }

    private record Product(long id, String codigo) {}
    private record Observation(long product, YearMonth month, BigDecimal quantity) {}

    @Transactional
    public ExecucaoDto create(CreateExecucao request, String email) {
        var lot = lots.get(request.loteHistoricoId());
        if (!lot.tipo().equals("HISTORICO") || !lot.estado().equals("VALIDADO")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seleciona um lote histórico validado.");
        }
        Instant cutoff = request.corteTemporal().truncatedTo(ChronoUnit.MICROS);
        YearMonth last = YearMonth.parse(request.ultimoPeriodoTreino());
        if (cutoff.isAfter(Instant.now()) || last.atEndOfMonth().isAfter(cutoff.atZone(ZoneOffset.UTC).toLocalDate())
                || last.plusMonths(request.horizonteMeses()).getYear() > 9999) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Corte sem futuro, posterior ao fecho do mês de treino, com horizonte até ao ano 9999.");
        }
        if (lot.disponivelEm().isAfter(cutoff) || lot.validadoEm().isAfter(cutoff)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O lote ainda não estava disponível e validado no corte indicado.");
        }
        var products = jdbc.query("SELECT DISTINCT p.id,p.codigo FROM produto p JOIN observacao o ON o.produto_id=p.id WHERE o.lote_id=? AND o.tipo=? ORDER BY p.id LIMIT 1001",
                (rs, n) -> new Product(rs.getLong("id"), rs.getString("codigo")), request.loteHistoricoId(), request.tipoSerie().name());
        if (products.isEmpty() || products.size() > 1000) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A execução exige entre 1 e 1000 produtos na série selecionada.");
        }
        int window = request.parametros().janela();
        YearMonth first = last.minusMonths(window - 1L);
        if (first.getYear() < 1000) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A janela de treino começa antes do ano 1000."); }
        var observations = jdbc.query("SELECT produto_id,periodo,quantidade FROM observacao WHERE lote_id=? AND tipo=? AND periodo BETWEEN ? AND ? AND disponivel_em<=? ORDER BY produto_id,periodo",
                (rs, n) -> new Observation(rs.getLong("produto_id"), YearMonth.from(rs.getDate("periodo").toLocalDate()), rs.getBigDecimal("quantidade")),
                request.loteHistoricoId(), request.tipoSerie().name(), first.atDay(1), last.atDay(1), utc(cutoff));
        var series = new HashMap<Long, NavigableMap<YearMonth, BigDecimal>>();
        for (var row : observations) { series.computeIfAbsent(row.product(), id -> new TreeMap<>()).put(row.month(), row.quantity()); }
        String params = "{\"janela\":" + window + ",\"multiplosPassos\":\"RECURSIVO\",\"escala\":4,\"arredondamento\":\"HALF_UP\",\"politicaLacunas\":\"SEM_IMPUTACAO\"}";
        long author = users.findByEmail(email).orElseThrow().getId();
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO execucao_previsao(lote_historico_id,tipo_serie,corte_temporal,ultimo_periodo_treino,
                      horizonte_meses,metodo_pedido,parametros,versao_software,versao_motor,estado,criado_por)
                    VALUES (?,?,?,?,?,'MEDIA_MOVEL',?,?,?,'EM_EXECUCAO',?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, request.loteHistoricoId()); ps.setString(2, request.tipoSerie().name());
            ps.setObject(3, utc(cutoff)); ps.setObject(4, last.atDay(1)); ps.setInt(5, request.horizonteMeses());
            ps.setString(6, params); ps.setString(7, softwareVersion); ps.setString(8, engine.version()); ps.setLong(9, author);
            return ps;
        }, key);
        long execution = Objects.requireNonNull(key.getKey()).longValue();
        for (var product : products) {
            var history = series.getOrDefault(product.id(), new TreeMap<>());
            var values = new ArrayList<BigDecimal>();
            var missing = new ArrayList<String>();
            for (int i = 0; i < window; i++) {
                YearMonth month = first.plusMonths(i);
                BigDecimal value = history.get(month);
                if (value == null) { missing.add(month.toString()); } else { values.add(value); }
            }
            if (!missing.isEmpty()) {
                jdbc.update("INSERT INTO execucao_produto(execucao_id,produto_id,parametros_aplicados,estado,diagnostico) VALUES (?,?,?,'INSUFICIENTE',?)",
                        execution, product.id(), params, "JANELA_INCOMPLETA: meses em falta ou indisponíveis no corte: " + String.join(", ", missing));
                continue;
            }
            jdbc.update("INSERT INTO execucao_produto(execucao_id,produto_id,metodo_aplicado,parametros_aplicados,estado) VALUES (?,?,'MEDIA_MOVEL',?,'PREVISTO')", execution, product.id(), params);
            var predictions = engine.forecast(values, request.horizonteMeses());
            var inserts = new ArrayList<Object[]>();
            for (int i = 0; i < predictions.size(); i++) {
                inserts.add(new Object[]{execution, product.id(), last.plusMonths(i + 1L).atDay(1), predictions.get(i)});
            }
            jdbc.batchUpdate("INSERT INTO linha_previsao(execucao_id,produto_id,periodo,quantidade_base) VALUES (?,?,?,?)", inserts);
        }
        jdbc.update("UPDATE execucao_previsao SET estado='CONCLUIDA',concluido_em=? WHERE id=?", utc(Instant.now().truncatedTo(ChronoUnit.MICROS)), execution);
        return queries.get(execution);
    }

    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
