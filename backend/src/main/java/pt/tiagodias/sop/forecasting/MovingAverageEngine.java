package pt.tiagodias.sop.forecasting;

import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MovingAverageEngine implements ForecastEngine {
    @Override public String method() { return "MEDIA_MOVEL"; }
    @Override public String version() { return "moving-average-recursive-v1"; }

    @Override
    public List<BigDecimal> forecast(List<BigDecimal> history, int horizon) {
        if (history.isEmpty() || horizon < 1) { throw new IllegalArgumentException("Histórico e horizonte inválidos."); }
        var window = new ArrayDeque<>(history);
        var result = new ArrayList<BigDecimal>();
        BigDecimal divisor = BigDecimal.valueOf(window.size());
        for (int step = 0; step < horizon; step++) {
            BigDecimal sum = window.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal next = sum.divide(divisor, 4, RoundingMode.HALF_UP);
            result.add(next);
            window.removeFirst(); window.addLast(next);
        }
        return List.copyOf(result);
    }
}
