package pt.tiagodias.sop.forecasting;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** ETS(A,A,N), com beta na forma de inovacoes, nao beta* de Holt. */
@Component
public class EtsAanEngine implements ForecastEngine {
    public record Fit(List<BigDecimal> predictions, Map<String, Object> parameters, String diagnostic) {}
    private record State(double level, double trend, double sse) {}
    @Override public String method() { return "ETS"; }
    @Override public String version() { return "ets-aan-grid-v1"; }
    @Override public List<BigDecimal> forecast(List<BigDecimal> history, int horizon) { return fit(history, horizon).predictions(); }

    public Fit fit(List<BigDecimal> history, int horizon) {
        validate(history, horizon);
        double bestScore = Double.POSITIVE_INFINITY;
        double bestAlpha = 0, bestBeta = 0;
        // Grelha de alpha e beta* = 0.1,...,0.9. beta=alpha*beta*.
        // Ordem crescente e substituicao apenas por SSE inferior tornam empates deterministas.
        for (int a = 1; a <= 9; a++) {
            for (int b = 1; b <= 9; b++) {
                double alpha = a / 10.0, beta = a * b / 100.0;
                double score = smooth(history, alpha, beta).sse();
                if (Double.isFinite(score) && score < bestScore) { bestScore = score; bestAlpha = alpha; bestBeta = beta; }
            }
        }
        if (!Double.isFinite(bestScore)) { throw new ArithmeticException("Não foi encontrado ajuste finito."); }
        var fit = fitFixed(history, horizon, bestAlpha, bestBeta);
        var parameters = new LinkedHashMap<>(fit.parameters());
        parameters.put("criterio", "SSE_TREINO_GRELHA_81");
        return new Fit(fit.predictions(), Collections.unmodifiableMap(parameters), fit.diagnostic());
    }

    /** Tambem permite verificacao contra um modelo de referencia com coeficientes fixos. */
    public Fit fitFixed(List<BigDecimal> history, int horizon, double alpha, double beta) {
        validate(history, horizon);
        if (!(alpha > 0 && alpha < 1 && beta > 0 && beta < alpha)) { throw new IllegalArgumentException("Exige 0 < beta < alpha < 1."); }
        State state = smooth(history, alpha, beta);
        var predictions = new ArrayList<BigDecimal>();
        int clipped = 0;
        for (int step = 1; step <= horizon; step++) {
            double value = state.level() + step * state.trend();
            if (!Double.isFinite(value)) { throw new ArithmeticException("Previsão não finita."); }
            if (value < 0) { value = 0; clipped++; }
            predictions.add(BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP));
        }
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("variante", "AAN"); parameters.put("alpha", alpha); parameters.put("beta", beta);
        parameters.put("betaDefinicao", "INOVACOES");
        parameters.put("nivelInicial", history.get(0));
        parameters.put("tendenciaInicial", history.get(1).subtract(history.get(0)));
        parameters.put("nivelFinal", state.level()); parameters.put("tendenciaFinal", state.trend());
        parameters.put("sseTreino", state.sse()); parameters.put("numeroObservacoes", history.size());
        parameters.put("inicializacao", "PRIMEIRO_VALOR_E_DIFERENCA_INICIAL");
        parameters.put("criterio", "COEFICIENTES_FIXOS_REFERENCIA"); parameters.put("previsoesTruncadasEmZero", clipped);
        return new Fit(List.copyOf(predictions), Collections.unmodifiableMap(parameters),
                clipped == 0 ? null : "PREVISOES_TRUNCADAS_EM_ZERO: " + clipped);
    }

    private State smooth(List<BigDecimal> history, double alpha, double beta) {
        double level = history.get(0).doubleValue();
        double trend = history.get(1).subtract(history.get(0)).doubleValue();
        double sse = 0;
        for (int i = 1; i < history.size(); i++) {
            double fitted = level + trend;
            double error = history.get(i).doubleValue() - fitted;
            sse += error * error;
            level = fitted + alpha * error;
            trend += beta * error;
        }
        return new State(level, trend, sse);
    }
    private void validate(List<BigDecimal> history, int horizon) {
        if (history.size() < 6 || history.size() > 120 || horizon < 1 || horizon > 12
                || history.stream().anyMatch(value -> value == null || value.signum() < 0)) {
            throw new IllegalArgumentException("ETS exige 6 a 120 observações não negativas e horizonte 1 a 12.");
        }
    }
}
