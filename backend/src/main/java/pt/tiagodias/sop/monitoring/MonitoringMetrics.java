package pt.tiagodias.sop.monitoring;

import java.math.*;
import java.util.*;
import pt.tiagodias.sop.evaluation.CommonMetrics;
import static pt.tiagodias.sop.monitoring.MonitoringDtos.*;

public final class MonitoringMetrics {
    public static final String VERSION="monitoring-common-pairs-v1";
    private MonitoringMetrics() {}
    public static String decimal(BigDecimal n) { return n==null?null:n.setScale(4,RoundingMode.HALF_UP).toPlainString(); }
    public static BigDecimal number(String n) { return n==null?null:new BigDecimal(n); }
    public static String ratio(BigDecimal n,BigDecimal d) { return n==null || d==null || d.signum()==0?null:decimal(n.multiply(BigDecimal.valueOf(100)).divide(d,18,RoundingMode.HALF_UP)); }
    private static String mean(BigDecimal sum,int n) { return n==0?null:decimal(sum.divide(BigDecimal.valueOf(n),18,RoundingMode.HALF_UP)); }
    public static Precisao precision(List<Celula> cells) {
        var first=cells.get(0);var byHorizon=new TreeMap<Integer,List<Celula>>();
        for(var cell:cells) { byHorizon.computeIfAbsent(cell.horizonte(),key->new ArrayList<>()).add(cell); }
        return new Precisao(first.produtoId(),first.produto(),first.unidade(),metrics(cells),byHorizon.entrySet().stream().map(e->new Horizonte(e.getKey(),metrics(e.getValue()))).toList());
    }
    public static Metricas metrics(List<Celula> cells) {
        var pairs=cells.stream().filter(Celula::elegivelPrecisao).map(c->new CommonMetrics.Pair(number(c.quantidadeBase()),number(c.quantidadeAprovada()),number(c.quantidadeRealizada()))).toList();
        var m=CommonMetrics.calculate(pairs);
        return new Metricas(cells.size(),m.paresComparaveis(),m.paresMape(),m.zerosExcluidosMape(),count(cells,"SEM_REALIZADO"),count(cells,"REALIZADO_APOS_CORTE"),count(cells,"PLANO_PUBLICADO_TARDIAMENTE"),count(cells,"PERIODO_NAO_FECHADO"),
                ratio(BigDecimal.valueOf(pairs.size()),BigDecimal.valueOf(cells.size())),m.maeMediaMovel(),m.maeEts(),m.mapeMediaMovel(),m.mapeEts(),m.maeMediaMovel()==null?null:decimal(number(m.maeMediaMovel()).subtract(number(m.maeEts()))));
    }
    private static int count(List<Celula> cells,String reason) { return (int)cells.stream().filter(c->reason.equals(c.motivoExclusao())).count(); }
    public static Operacao operation(List<Celula> cells) {
        int temporal=0,known=0,service=0,rupture=0,stock=0,loss=0;
        BigDecimal requested=BigDecimal.ZERO,satisfied=BigDecimal.ZERO,days=BigDecimal.ZERO,out=BigDecimal.ZERO,excess=BigDecimal.ZERO,lost=BigDecimal.ZERO;
        for(var cell:cells) {
            if(cell.elegivelTemporal()) { temporal++; }
            var op=cell.operacional();if(op==null) { continue; }known++;
            if(op.unidadesSolicitadas()!=null && op.unidadesSatisfeitasNoPrazo()!=null) { service++;requested=requested.add(number(op.unidadesSolicitadas()));satisfied=satisfied.add(number(op.unidadesSatisfeitasNoPrazo())); }
            if(op.diasObservadosComProcura()!=null && op.diasComProcuraSemStock()!=null) { rupture++;days=days.add(BigDecimal.valueOf(op.diasObservadosComProcura()));out=out.add(BigDecimal.valueOf(op.diasComProcuraSemStock())); }
            if(op.excessoStock()!=null) { stock++;excess=excess.add(number(op.excessoStock())); }
            if(op.unidadesPerdidas()!=null) { loss++;lost=lost.add(number(op.unidadesPerdidas())); }
        }
        var first=cells.get(0);
        return new Operacao(first.produtoId(),first.produto(),first.unidade(),cells.size(),temporal,known,service,rupture,stock,loss,ratio(satisfied,requested),ratio(out,days),mean(excess,stock),loss==0?null:decimal(lost));
    }
}
