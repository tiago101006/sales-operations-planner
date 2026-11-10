package pt.tiagodias.sop;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import pt.tiagodias.sop.evaluation.CommonMetrics;
import static org.assertj.core.api.Assertions.*;

class CommonMetricsTests {
    private CommonMetrics.Pair pair(String mm,String ets,String actual) {
        return new CommonMetrics.Pair(mm==null?null:new BigDecimal(mm),ets==null?null:new BigDecimal(ets),actual==null?null:new BigDecimal(actual));
    }
    @Test
    void reproducesReferenceWithZerosAndMissingActuals() {
        var metrics=CommonMetrics.calculate(List.of(pair("90","110","100"),pair("10","0","0"),pair("30","20",null)));
        assertThat(metrics.maeMediaMovel()).isEqualTo("10.0000");assertThat(metrics.maeEts()).isEqualTo("5.0000");
        assertThat(metrics.mapeMediaMovel()).isEqualTo("10.0000");assertThat(metrics.mapeEts()).isEqualTo("10.0000");
        assertThat(metrics.paresComparaveis()).isEqualTo(2);assertThat(metrics.paresMape()).isEqualTo(1);
        assertThat(metrics.zerosExcluidosMape()).isEqualTo(1);assertThat(metrics.realizadosEmFalta()).isEqualTo(1);
        assertThat(metrics.coberturaPercentagem()).isEqualTo("66.6667");
    }
    @Test
    void comparesOnlyCommonPairsAndReturnsNullWhenUndefined() {
        var metrics=CommonMetrics.calculate(List.of(pair("90",null,"100"),pair(null,"100","100")));
        assertThat(metrics.paresComparaveis()).isZero();assertThat(metrics.maeMediaMovel()).isNull();assertThat(metrics.maeEts()).isNull();
        assertThat(metrics.mapeMediaMovel()).isNull();assertThat(metrics.semPrevisaoMediaMovel()).isEqualTo(1);assertThat(metrics.semPrevisaoEts()).isEqualTo(1);
        var zeros=CommonMetrics.calculate(List.of(pair("5","0","0")));
        assertThat(zeros.maeMediaMovel()).isEqualTo("5.0000");assertThat(zeros.maeEts()).isEqualTo("0.0000");assertThat(zeros.mapeEts()).isNull();
        assertThat(CommonMetrics.calculate(List.of()).coberturaPercentagem()).isNull();
    }
    @Test
    void calculatesMeanOfPercentageErrorsRatherThanRatioOfTotals() {
        var metrics=CommonMetrics.calculate(List.of(pair("20","10","10"),pair("110","100","100")));
        assertThat(metrics.mapeMediaMovel()).isEqualTo("55.0000");assertThat(metrics.mapeEts()).isEqualTo("0.0000");
    }
}
