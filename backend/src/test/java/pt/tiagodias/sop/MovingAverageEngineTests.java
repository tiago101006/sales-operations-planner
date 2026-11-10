package pt.tiagodias.sop;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import pt.tiagodias.sop.forecasting.MovingAverageEngine;
import static org.assertj.core.api.Assertions.*;

class MovingAverageEngineTests {
    private final MovingAverageEngine engine = new MovingAverageEngine();
    private List<BigDecimal> decimals(String... values) { return java.util.Arrays.stream(values).map(BigDecimal::new).toList(); }

    @Test
    void recursivelyForecastsAndPreservesInput() {
        var history = decimals("10", "20", "30");
        assertThat(engine.forecast(history, 3)).containsExactlyElementsOf(decimals("20.0000", "23.3333", "24.4444"));
        assertThat(history).containsExactlyElementsOf(decimals("10", "20", "30"));
        assertThat(engine.forecast(history, 3)).isEqualTo(engine.forecast(history, 3));
    }
    @Test
    void includesObservedZeros() {
        assertThat(engine.forecast(decimals("10", "0", "20"), 3)).containsExactlyElementsOf(decimals("10.0000", "10.0000", "13.3333"));
        assertThat(engine.forecast(decimals("0", "0", "0"), 3)).allMatch(value -> value.equals(new BigDecimal("0.0000")));
    }
    @Test
    void roundsEachRecursiveStepToPublishedScale() {
        assertThat(engine.forecast(decimals("0", "0", "1"), 3)).containsExactlyElementsOf(decimals("0.3333", "0.4444", "0.5926"));
    }
    @Test
    void handlesSingleValueAndMaximumQuantityWithoutFloatingPoint() {
        assertThat(engine.forecast(decimals("99999999999999.9999"), 12)).hasSize(12).allMatch(value -> value.equals(new BigDecimal("99999999999999.9999")));
        assertThatThrownBy(() -> engine.forecast(List.of(), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.forecast(decimals("1"), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
