package pt.tiagodias.sop.evaluation;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Component;
import pt.tiagodias.sop.forecasting.*;

@Component
public class BacktestModels {
    private static final BigDecimal MAX = new BigDecimal("99999999999999.9999");
    private final MovingAverageEngine moving;
    private final EtsAanEngine ets;
    public BacktestModels(MovingAverageEngine moving,EtsAanEngine ets) { this.moving=moving;this.ets=ets; }
    public record Forecast(String state,List<BigDecimal> values,Map<String,Object> parameters,String diagnostic) {}
    private List<BigDecimal> window(NavigableMap<YearMonth,BigDecimal> series,YearMonth last,int size) {
        var values=new ArrayList<BigDecimal>();
        for(int i=0;i<size;i++) {
            var value=series.get(last.minusMonths(size-1L).plusMonths(i));
            if(value==null) { return List.of(); }
            values.add(value);
        }
        return values;
    }
    public Forecast moving(NavigableMap<YearMonth,BigDecimal> series,YearMonth last,int size,int horizon) {
        var params=Map.<String,Object>of("janela",size,"multiplosPassos","RECURSIVO","escala",4,"arredondamento","HALF_UP");
        var values=window(series,last,size);
        return values.isEmpty() ? new Forecast("INSUFICIENTE",List.of(),params,"JANELA_INCOMPLETA")
                : new Forecast("PREVISTO",moving.forecast(values,horizon),params,null);
    }
    public Forecast ets(NavigableMap<YearMonth,BigDecimal> series,YearMonth last,int lookback,int horizon) {
        var selected=series.tailMap(last.minusMonths(lookback-1L),true);
        var params=Map.<String,Object>of("variante","AAN","historicoMeses",lookback,"recurso","SEM_RECURSO");
        if(selected.isEmpty()) { return new Forecast("INSUFICIENTE",List.of(),params,"ETS_SEM_HISTORICO"); }
        var values=window(selected,last,(int)ChronoUnit.MONTHS.between(selected.firstKey(),last)+1);
        if(values.size()<6) { return new Forecast("INSUFICIENTE",List.of(),params,"ETS_LACUNAS_OU_HISTORICO_CURTO"); }
        try {
            var fit=ets.fit(values,horizon);
            var applied=new LinkedHashMap<>(params);applied.putAll(fit.parameters());
            applied.put("inicioTreino",selected.firstKey().toString());applied.put("fimTreino",last.toString());
            if(fit.predictions().stream().anyMatch(value->value.compareTo(MAX)>0)) {
                return new Forecast("ERRO",List.of(),applied,"PREVISAO_FORA_LIMITE");
            }
            return new Forecast("PREVISTO",fit.predictions(),applied,fit.diagnostic());
        } catch(ArithmeticException exception) { return new Forecast("ERRO",List.of(),params,"ERRO_NUMERICO"); }
    }
    public String movingVersion() { return moving.version(); }
    public String etsVersion() { return ets.version(); }
}
