package pt.tiagodias.sop.forecasting;

import java.math.BigDecimal;
import java.util.List;

/** Recebe valores cronologicos preparados; nao consulta nem altera a base. */
public interface ForecastEngine {
    String method();
    String version();
    List<BigDecimal> forecast(List<BigDecimal> preparedHistory, int horizon);
}
