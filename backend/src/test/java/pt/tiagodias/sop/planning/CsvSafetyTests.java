package pt.tiagodias.sop.planning;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CsvSafetyTests {
    @Test
    void neutralizesSpreadsheetFormulasIncludingWhitespaceAndUnicodePrefixes() {
        for (String text : List.of("=1+1", "+SUM(A1:A2)", "-1+2", "@SUM(A1)", "\tSKU", "\rSKU", "\nSKU",
                "  =1+1", "\u00a0=1+1", "\uFEFF=1+1", "＝1+1", "＋1", "－1", "＠SUM(A1)")) {
            assertThat(PlanQueries.safeCsvText(text)).isEqualTo("'" + text);
        }
    }

    @Test
    void preservesOrdinaryCodesAndUnitsWithoutChangingEmbeddedCsvCharacters() {
        for (String text : List.of("SKU001", "DEMO-1", "un", "kg;\"açúcar\"\nmedido", "", "'literal")) {
            assertThat(PlanQueries.safeCsvText(text)).isEqualTo(text);
        }
    }
}
