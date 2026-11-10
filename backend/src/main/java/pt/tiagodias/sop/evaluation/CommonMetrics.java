package pt.tiagodias.sop.evaluation;

import java.math.*;
import java.util.List;
import static pt.tiagodias.sop.evaluation.BacktestDtos.*;

public final class CommonMetrics {
    public static final String VERSION = "common-pairs-v1";
    private CommonMetrics() {}
    public record Pair(BigDecimal moving, BigDecimal ets, BigDecimal actual) {}

    public static Metricas calculate(List<Pair> pairs) {
        int valid=0, mape=0, zeros=0, missing=0, noMoving=0, noEts=0;
        BigDecimal absMoving=BigDecimal.ZERO, absEts=BigDecimal.ZERO;
        BigDecimal percentMoving=BigDecimal.ZERO, percentEts=BigDecimal.ZERO;
        for (var pair : pairs) {
            if (pair.actual()==null) { missing++; }
            if (pair.moving()==null) { noMoving++; }
            if (pair.ets()==null) { noEts++; }
            if (pair.actual()==null || pair.moving()==null || pair.ets()==null) { continue; }
            valid++;
            var a = pair.moving().subtract(pair.actual()).abs();
            var b = pair.ets().subtract(pair.actual()).abs();
            absMoving=absMoving.add(a); absEts=absEts.add(b);
            if (pair.actual().signum()==0) { zeros++; continue; }
            mape++;
            percentMoving=percentMoving.add(a.divide(pair.actual().abs(),18,RoundingMode.HALF_UP));
            percentEts=percentEts.add(b.divide(pair.actual().abs(),18,RoundingMode.HALF_UP));
        }
        return new Metricas(pairs.size(),valid,mape,zeros,missing,noMoving,noEts,
                mean(BigDecimal.valueOf(valid).multiply(BigDecimal.valueOf(100)),pairs.size()),
                mean(absMoving,valid),mean(absEts,valid),mean(percentMoving.multiply(BigDecimal.valueOf(100)),mape),mean(percentEts.multiply(BigDecimal.valueOf(100)),mape));
    }
    private static String mean(BigDecimal sum,int count) {
        return count==0 ? null : sum.divide(BigDecimal.valueOf(count),4,RoundingMode.HALF_UP).toPlainString();
    }
}
