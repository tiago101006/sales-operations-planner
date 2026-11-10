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
import tools.jackson.databind.json.JsonMapper;
import static pt.tiagodias.sop.forecasting.ForecastDtos.*;

@Service
public class ForecastService {
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999999999.9999");
    private final JdbcTemplate jdbc;
    private final ImportQueries lots;
    private final UtilizadorRepository users;
    private final ForecastQueries queries;
    private final MovingAverageEngine moving;
    private final EtsAanEngine ets;
    private final String softwareVersion;
    private final JsonMapper json = JsonMapper.builder().build();

    public ForecastService(JdbcTemplate jdbc, ImportQueries lots, UtilizadorRepository users,
                           ForecastQueries queries, MovingAverageEngine moving, EtsAanEngine ets,
                           @Value("${sop.software-version}") String softwareVersion) {
        this.jdbc = jdbc; this.lots = lots; this.users = users;
        this.queries = queries; this.moving = moving; this.ets = ets; this.softwareVersion = softwareVersion;
    }

    private record Product(long id, String codigo) {}
    private record Observation(long product, YearMonth month, BigDecimal quantity) {}
    private record Window(List<BigDecimal> values, List<String> missing) {}
    private record Outcome(String state, String method, Map<String, Object> parameters, List<BigDecimal> values, String diagnostic) {}

    private Map<String, Object> movingConfig(int window) {
        var config = new LinkedHashMap<String, Object>();
        config.put("janela", window); config.put("multiplosPassos", "RECURSIVO"); config.put("escala", 4);
        config.put("arredondamento", "HALF_UP"); config.put("politicaLacunas", "SEM_IMPUTACAO");
        return config;
    }

    private Map<String, Object> configuration(CreateExecucao request) {
        var p = request.parametros();
        if (request.metodo().equals("MEDIA_MOVEL")) {
            if (p.janela() == null || p.variante() != null || p.historicoMeses() != null || p.recurso() != null || p.janelaRecurso() != null) {
                throw invalid("Média Móvel exige janela e não aceita parâmetros ETS.");
            }
            return movingConfig(p.janela());
        }
        if (p.janela() != null || p.multiplosPassos() != null) { throw invalid("ETS usa historicoMeses, não janela/multiplosPassos."); }
        String fallback = p.recurso() == null ? "SEM_RECURSO" : p.recurso();
        if (fallback.equals("SEM_RECURSO") && p.janelaRecurso() != null) { throw invalid("janelaRecurso exige recurso MEDIA_MOVEL."); }
        var config = new LinkedHashMap<String, Object>();
        config.put("variante", "AAN"); config.put("historicoMeses", p.historicoMeses() == null ? 24 : p.historicoMeses());
        config.put("minimoObservacoes", 6); config.put("recurso", fallback);
        if (fallback.equals("MEDIA_MOVEL")) { config.put("janelaRecurso", p.janelaRecurso() == null ? 3 : p.janelaRecurso()); }
        config.put("escala", 4); config.put("arredondamento", "HALF_UP"); config.put("politicaLacunas", "SEM_IMPUTACAO");
        config.put("politicaNegativos", "TRUNCAR_ZERO");
        config.put("grelhaAlpha", List.of(0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9));
        config.put("grelhaBetaEstrela", List.of(0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9));
        config.put("inicializacao", "PRIMEIRO_VALOR_E_DIFERENCA_INICIAL");
        return config;
    }

    @Transactional
    public ExecucaoDto create(CreateExecucao request, String email) {
        var config = configuration(request);
        boolean isEts = request.metodo().equals("ETS");
        int historyMonths = (int) config.get(isEts ? "historicoMeses" : "janela");
        int fallbackWindow = config.containsKey("janelaRecurso") ? (int) config.get("janelaRecurso") : 0;
        var lot = lots.get(request.loteHistoricoId());
        if (!lot.tipo().equals("HISTORICO") || !lot.estado().equals("VALIDADO")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seleciona um lote histórico validado.");
        }
        Instant cutoff = request.corteTemporal().truncatedTo(ChronoUnit.MICROS);
        YearMonth last = YearMonth.parse(request.ultimoPeriodoTreino());
        if (cutoff.isAfter(Instant.now()) || last.atEndOfMonth().isAfter(cutoff.atZone(ZoneOffset.UTC).toLocalDate())
                || last.plusMonths(request.horizonteMeses()).getYear() > 9999) {
            throw invalid("Corte sem futuro, posterior ao fecho do mês de treino, com horizonte até ao ano 9999.");
        }
        if (lot.disponivelEm().isAfter(cutoff) || lot.validadoEm().isAfter(cutoff)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O lote ainda não estava disponível e validado no corte indicado.");
        }
        var products = jdbc.query("SELECT DISTINCT p.id,p.codigo FROM produto p JOIN observacao o ON o.produto_id=p.id WHERE o.lote_id=? AND o.tipo=? ORDER BY p.id LIMIT 1001",
                (rs, n) -> new Product(rs.getLong("id"), rs.getString("codigo")), request.loteHistoricoId(), request.tipoSerie().name());
        if (products.isEmpty() || products.size() > 1000) { throw invalid("A execução exige entre 1 e 1000 produtos na série selecionada."); }
        YearMonth first = last.minusMonths(Math.max(historyMonths, fallbackWindow) - 1L);
        if (first.getYear() < 1000) { throw invalid("A janela de treino começa antes do ano 1000."); }
        var observations = jdbc.query("SELECT produto_id,periodo,quantidade FROM observacao WHERE lote_id=? AND tipo=? AND periodo BETWEEN ? AND ? AND disponivel_em<=? ORDER BY produto_id,periodo",
                (rs, n) -> new Observation(rs.getLong("produto_id"), YearMonth.from(rs.getDate("periodo").toLocalDate()), rs.getBigDecimal("quantidade")),
                request.loteHistoricoId(), request.tipoSerie().name(), first.atDay(1), last.atDay(1), utc(cutoff));
        var series = new HashMap<Long, NavigableMap<YearMonth, BigDecimal>>();
        for (var row : observations) { series.computeIfAbsent(row.product(), id -> new TreeMap<>()).put(row.month(), row.quantity()); }
        long author = users.findByEmail(email).orElseThrow().getId();
        String version = isEts ? ets.version() + (fallbackWindow > 0 ? ";fallback:" + moving.version() : "") : moving.version();
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO execucao_previsao(lote_historico_id,tipo_serie,corte_temporal,ultimo_periodo_treino,
                      horizonte_meses,metodo_pedido,parametros,versao_software,versao_motor,estado,criado_por)
                    VALUES (?,?,?,?,?,?,?,?,?,'EM_EXECUCAO',?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, request.loteHistoricoId()); ps.setString(2, request.tipoSerie().name());
            ps.setObject(3, utc(cutoff)); ps.setObject(4, last.atDay(1)); ps.setInt(5, request.horizonteMeses());
            ps.setString(6, request.metodo()); ps.setString(7, json.writeValueAsString(config));
            ps.setString(8, softwareVersion); ps.setString(9, version); ps.setLong(10, author);
            return ps;
        }, key);
        long execution = Objects.requireNonNull(key.getKey()).longValue();
        for (var product : products) {
            var history = series.getOrDefault(product.id(), new TreeMap<>());
            Outcome result = isEts ? etsOutcome(history, last, request.horizonteMeses(), historyMonths, fallbackWindow, config)
                    : movingOutcome(history, last, request.horizonteMeses(), historyMonths);
            jdbc.update("INSERT INTO execucao_produto(execucao_id,produto_id,metodo_aplicado,parametros_aplicados,estado,diagnostico) VALUES (?,?,?,?,?,?)",
                    execution, product.id(), result.method(), json.writeValueAsString(result.parameters()), result.state(), result.diagnostic());
            if (result.values().isEmpty()) { continue; }
            var inserts = new ArrayList<Object[]>();
            for (int i = 0; i < result.values().size(); i++) {
                inserts.add(new Object[]{execution, product.id(), last.plusMonths(i + 1L).atDay(1), result.values().get(i)});
            }
            jdbc.batchUpdate("INSERT INTO linha_previsao(execucao_id,produto_id,periodo,quantidade_base) VALUES (?,?,?,?)", inserts);
        }
        jdbc.update("UPDATE execucao_previsao SET estado='CONCLUIDA',concluido_em=? WHERE id=?", utc(Instant.now().truncatedTo(ChronoUnit.MICROS)), execution);
        return queries.get(execution);
    }

    private Window window(NavigableMap<YearMonth, BigDecimal> history, YearMonth last, int size) {
        var values = new ArrayList<BigDecimal>(); var missing = new ArrayList<String>();
        YearMonth first = last.minusMonths(size - 1L);
        for (int i = 0; i < size; i++) {
            var month = first.plusMonths(i); var value = history.get(month);
            if (value == null) { missing.add(month.toString()); } else { values.add(value); }
        }
        return new Window(values, missing);
    }
    private String gaps(List<String> missing) {
        return String.join(", ", missing.stream().limit(12).toList()) + (missing.size() > 12 ? " (" + missing.size() + " meses no total)" : "");
    }
    private Outcome movingOutcome(NavigableMap<YearMonth, BigDecimal> history, YearMonth last, int horizon, int size) {
        var window = window(history, last, size); var params = movingConfig(size);
        if (!window.missing().isEmpty()) {
            return new Outcome("INSUFICIENTE", null, params, List.of(), "JANELA_INCOMPLETA: meses em falta ou indisponíveis no corte: " + gaps(window.missing()));
        }
        return new Outcome("PREVISTO", "MEDIA_MOVEL", params, moving.forecast(window.values(), horizon), null);
    }
    private Outcome etsOutcome(NavigableMap<YearMonth, BigDecimal> history, YearMonth last, int horizon,
                               int lookback, int fallbackWindow, Map<String, Object> config) {
        var selected = history.tailMap(last.minusMonths(lookback - 1L), true);
        Window prepared = selected.isEmpty() ? new Window(List.of(), List.of(last.toString()))
                : window(selected, last, (int) ChronoUnit.MONTHS.between(selected.firstKey(), last) + 1);
        String reason = !prepared.missing().isEmpty() ? "ETS_LACUNAS: " + gaps(prepared.missing())
                : prepared.values().size() < 6 ? "ETS_HISTORICO_CURTO: requer 6 observações consecutivas; disponíveis " + prepared.values().size() : null;
        if (reason != null) {
            if (fallbackWindow > 0) {
                var fallback = movingOutcome(history, last, horizon, fallbackWindow);
                if (!fallback.values().isEmpty()) {
                    var applied = new LinkedHashMap<>(fallback.parameters());
                    applied.put("metodoPedido", "ETS"); applied.put("motivoRecurso", reason);
                    return new Outcome("RECURSO", "MEDIA_MOVEL", applied, fallback.values(), reason);
                }
                reason += "; RECURSO_INSUFICIENTE: " + fallback.diagnostic();
            }
            return new Outcome("INSUFICIENTE", null, config, List.of(), reason);
        }
        try {
            var fit = ets.fit(prepared.values(), horizon);
            var applied = new LinkedHashMap<>(config); applied.putAll(fit.parameters());
            applied.put("inicioTreino", selected.firstKey().toString()); applied.put("fimTreino", last.toString());
            if (fit.predictions().stream().anyMatch(value -> value.compareTo(MAX_QUANTITY) > 0)) {
                return new Outcome("ERRO", "ETS(A,A,N)", applied, List.of(), "PREVISAO_FORA_LIMITE: excede a capacidade DECIMAL(18,4).");
            }
            return new Outcome("PREVISTO", "ETS(A,A,N)", applied, fit.predictions(), fit.diagnostic());
        } catch (ArithmeticException exception) {
            return new Outcome("ERRO", "ETS(A,A,N)", config, List.of(), "ERRO_NUMERICO: ajuste ou previsão não finitos.");
        }
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
