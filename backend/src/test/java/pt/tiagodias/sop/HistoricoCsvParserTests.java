package pt.tiagodias.sop;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import pt.tiagodias.sop.imports.HistoricoCsvParser;
import static org.assertj.core.api.Assertions.*;

class HistoricoCsvParserTests {
    private final HistoricoCsvParser parser = new HistoricoCsvParser();
    private static final String HEADER = "produto;periodo;tipo;quantidade;unidade;disponivel_em\n";
    private final Instant now = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void enforcesRecordLimitAndBoundsDiagnostics() {
        var csv = new StringBuilder(HEADER);
        for (int i = 0; i < 100001; i++) { csv.append("SKU").append(i).append(";2025-01;VENDAS;1;un;2025-02-01T00:00:00Z\n"); }
        var parsed = parser.parse(csv.toString().getBytes(StandardCharsets.UTF_8), now);
        assertThat(parsed.totalLinhas()).isEqualTo(100001);
        assertThat(parsed.linhas()).hasSize(100000);
        assertThat(parsed.erros()).anyMatch(e -> e.codigo().equals("LIMITE_LINHAS"));
        var many = parser.parse((HEADER + "SKU;2025-01;VENDAS;1;un;2025-02-01T00:00:00Z\n".repeat(1100)).getBytes(StandardCharsets.UTF_8), now);
        assertThat(many.erros()).hasSize(1001);
        assertThat(many.erros().get(1000).codigo()).isEqualTo("DIAGNOSTICOS_LIMITADOS");
    }

    @Test
    void rejectsPrecisionOverflowMissingQuantityAndPrematureAvailability() {
        for (String value : new String[]{"", "-1", "1,5", "1.00001", "100000000000000", "1e3"}) {
            var parsed = parser.parse((HEADER + "SKU;2025-01;VENDAS;" + value + ";un;2025-02-01T00:00:00Z\n").getBytes(StandardCharsets.UTF_8), now);
            assertThat(parsed.erros()).anyMatch(e -> e.codigo().equals("QUANTIDADE_INVALIDA"));
        }
        var parsed = parser.parse((HEADER + "SKU;2025-01;VENDAS;10;un;2025-01-01T00:00:00Z\n").getBytes(StandardCharsets.UTF_8), now);
        assertThat(parsed.erros()).anyMatch(e -> e.codigo().equals("DISPONIBILIDADE_INVALIDA"));
    }
}
