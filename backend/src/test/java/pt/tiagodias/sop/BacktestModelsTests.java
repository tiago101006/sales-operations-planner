package pt.tiagodias.sop;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;
import pt.tiagodias.sop.evaluation.BacktestModels;
import pt.tiagodias.sop.forecasting.*;
import static org.assertj.core.api.Assertions.*;

class BacktestModelsTests {
    @Test
    void usesSameEnginesAndPreparedHistoryAsOperationalForecasts() {
        var moving=new MovingAverageEngine();var ets=new EtsAanEngine();var models=new BacktestModels(moving,ets);
        var history=new TreeMap<YearMonth,BigDecimal>();var values=new ArrayList<BigDecimal>();
        for(int i=1;i<=8;i++) { var value=BigDecimal.valueOf(i*10);history.put(YearMonth.of(2025,i),value);values.add(value); }
        history.put(YearMonth.of(2025,9),new BigDecimal("99999"));
        assertThat(models.moving(history,YearMonth.of(2025,8),3,3).values()).isEqualTo(moving.forecast(values.subList(5,8),3));
        assertThat(models.ets(history,YearMonth.of(2025,8),24,3).values()).isEqualTo(ets.fit(values,3).predictions());
    }
    @Test
    void neverSubstitutesMovingAverageWhenEtsHistoryIsInsufficient() {
        var models=new BacktestModels(new MovingAverageEngine(),new EtsAanEngine());
        var series=new TreeMap<YearMonth,BigDecimal>();for(int i=1;i<=3;i++) { series.put(YearMonth.of(2025,i),BigDecimal.TEN); }
        assertThat(models.moving(series,YearMonth.of(2025,3),3,3).state()).isEqualTo("PREVISTO");
        assertThat(models.ets(series,YearMonth.of(2025,3),24,3).state()).isEqualTo("INSUFICIENTE");
    }
}
