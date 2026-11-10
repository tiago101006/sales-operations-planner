package pt.tiagodias.sop;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import pt.tiagodias.sop.forecasting.EtsAanEngine;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class EtsAanEngineTests {
    private final EtsAanEngine engine = new EtsAanEngine();

    @Test
    void matchesIndependentStatsmodelsReferenceForFixedAndGridFittedModels() throws Exception {
        try (var stream = getClass().getResourceAsStream("/ets/reference-statsmodels.json")) {
            var root = JsonMapper.builder().build().readTree(stream);
            assertThat(root.get("cases").size()).isEqualTo(8);
            for (var reference : root.get("cases")) {
                var history = new ArrayList<BigDecimal>();
                for (var value : reference.get("history")) { history.add(new BigDecimal(value.asText())); }
                var fit = reference.get("fixed").asBoolean()
                        ? engine.fitFixed(history, 3, reference.get("alpha").asDouble(), reference.get("beta").asDouble()) : engine.fit(history, 3);
                assertThat(fit.predictions().stream().map(BigDecimal::toPlainString).toList()).as(reference.get("name").asText())
                        .containsExactly(reference.get("forecast").get(0).asText(), reference.get("forecast").get(1).asText(), reference.get("forecast").get(2).asText());
                for (String key : List.of("alpha", "beta")) {
                    assertThat(((Number) fit.parameters().get(key)).doubleValue()).isCloseTo(reference.get(key).asDouble(), within(1e-12));
                }
                assertThat(((Number) fit.parameters().get("sseTreino")).doubleValue()).isCloseTo(reference.get("sse").asDouble(), within(1e-8));
                assertThat(((Number) fit.parameters().get("nivelFinal")).doubleValue()).isCloseTo(reference.get("level").asDouble(), within(1e-8));
                assertThat(((Number) fit.parameters().get("tendenciaFinal")).doubleValue()).isCloseTo(reference.get("trend").asDouble(), within(1e-8));
            }
        }
    }

    @Test
    void reportsClippingAndKeepsInputUnchanged() {
        var history = List.of(new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("20"), BigDecimal.ZERO);
        var first = engine.fit(history, 3); var second = engine.fit(history, 3);
        assertThat(first).isEqualTo(second);
        assertThat(first.predictions()).allMatch(value -> value.equals(new BigDecimal("0.0000")));
        assertThat(first.diagnostic()).contains("TRUNCADAS_EM_ZERO: 3");
        assertThat(history.get(5)).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void rejectsInsufficientHistoryAndInvalidCoefficients() {
        assertThatThrownBy(() -> engine.fit(List.of(BigDecimal.ONE), 3)).isInstanceOf(IllegalArgumentException.class);
        var history = Collections.nCopies(6, BigDecimal.ONE);
        assertThatThrownBy(() -> engine.fitFixed(history, 3, .5, .6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.fit(history, 13)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.fit(Collections.nCopies(6, BigDecimal.ONE.negate()), 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
