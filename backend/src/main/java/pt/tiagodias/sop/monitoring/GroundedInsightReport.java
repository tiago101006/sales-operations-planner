package pt.tiagodias.sop.monitoring;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import static pt.tiagodias.sop.monitoring.InsightDtos.*;
import static pt.tiagodias.sop.monitoring.MonitoringDtos.*;

/** All claims and quantities come from the immutable assessment; AI may only order eligible actions. */
public final class GroundedInsightReport {
    private static final int SUMMARY_PRODUCTS = 8;
    private GroundedInsightReport() {}
    public record Recommendation(String id, String titulo, String texto) {}

    public static List<Recommendation> recommendations(Snapshot s) {
        var actions=new ArrayList<Recommendation>();
        add(actions,s,"PRESERVAR_EXCLUSAO_TARDIA","Preservar a exclusão por publicação tardia",
                p->p.precisao().metricas().planosTardios()>0,
                "Confere os meses classificados como publicação tardia no detalhe. A data original do plano é preservada: novos realizados, outro corte ou esperar não tornam essas células elegíveis. Para períodos futuros, prepara e publica o plano antes do início do mês; a exclusão histórica mantém-se.");
        add(actions,s,"AVALIAR_MESES_FECHADOS","Avaliar os meses depois do seu fecho",
                p->p.precisao().metricas().periodosNaoFechados()>0,
                "Quando os meses ainda abertos estiverem fechados e os resultados disponíveis, cria outra avaliação com os lotes e o corte adequados. A avaliação atual permanece fixa. A elegibilidade da nova avaliação continua sujeita às restantes regras, incluindo a data de publicação do plano.");
        add(actions,s,"COMPLETAR_REALIZADOS","Completar os realizados em falta",
                p->p.precisao().metricas().emFalta()>0,
                "Confere os produtos e meses classificados como SEM_REALIZADO, importa um lote completo e validado com os valores observados e cria uma nova avaliação. Não substituas valores em falta por zero: zero é um resultado observado e altera o cálculo dos erros.");
        add(actions,s,"REVER_CORTE_REALIZADOS","Usar um corte que inclua os realizados disponíveis",
                p->p.precisao().metricas().realizadosAposCorte()>0,
                "Verifica as datas de disponibilidade dos realizados classificados após o corte. Se o objetivo for incluir esses resultados, cria outra avaliação com um corte posterior adequado. Mantém a anterior para comparação e não alteres as datas de disponibilidade para forçar elegibilidade.");
        add(actions,s,"INTERPRETAR_ZEROS_MAPE","Interpretar os realizados zero separadamente",
                p->p.precisao().metricas().zerosExcluidosMape()>0,
                "Usa o MAE para observar os erros destes meses. O MAPE exclui realizados zero por não permitir divisão por zero; confirma o número de pares de cada indicador antes de os comparar. Não interpretes a ausência de MAPE como ausência de procura planeada nem como erro nulo.");
        add(actions,s,"REVER_AJUSTES_COM_PIOR_MAE","Rever os ajustes com agravamento observado do MAE",
                p->negative(p.precisao().metricas().ganhoAjuste()),
                "Compara os valores base, aprovados e realizados dos mesmos pares elegíveis e consulta as justificações dos ajustes. Um ganho negativo significa MAE aprovado superior ao MAE base nesta amostra; não demonstra uma causa nem permite generalizar para períodos sem realizado. Prepara outra revisão se houver fundamento para corrigir planos futuros.");
        add(actions,s,"ACOMPANHAR_AJUSTES_AVALIAVEIS","Acompanhar o efeito dos ajustes avaliáveis",
                p->p.precisao().metricas().ganhoAjuste()!=null&&!negative(p.precisao().metricas().ganhoAjuste()),
                "Confere o ganho do ajuste e a cobertura no detalhe por produto. Um ganho positivo indica redução observada do MAE; zero indica igualdade à precisão apresentada. Conserva a comparação nos mesmos pares e acompanha novas avaliações antes de concluir que a melhoria se mantém.");
        add(actions,s,"COMPLETAR_OPERACAO","Completar a cobertura operacional",
                p->p.operacao().registosConhecidos()<p.operacao().candidatos(),
                "Consulta os motivos operacionais de cada célula: regra temporal, ausência de lote, registo em falta ou disponibilidade após o corte. Reúne os dados operacionais aplicáveis e cria outra avaliação quando elegíveis. Estes dados sustentam serviço, ruturas, stock e perdas; não são requisito para calcular MAE ou MAPE.");
        add(actions,s,"CONFERIR_SERVICO_RUTURA","Conferir serviço e ruturas com os seus denominadores",
                p->p.operacao().paresServico()>0||p.operacao().paresRutura()>0,
                "Confere unidades solicitadas e satisfeitas no prazo para o nível de serviço, e dias observados com procura e sem stock para a rutura. Os rácios usam os denominadores somados, não uma média simples das percentagens mensais. A avaliação não contém uma meta de serviço nem um limiar aceitável de rutura.");
        add(actions,s,"CONFERIR_EXCESSO_STOCK","Conferir o excesso de stock observado",
                p->p.operacao().paresStock()>0,
                "Compara o stock de fecho com o limite de cada mês. O excesso é a parte positiva da diferença, e o indicador é a média apenas dos meses com ambos os valores. Mantém a unidade do produto; o excesso não permite calcular custo de posse nem provar a origem do stock.");
        add(actions,s,"CONFERIR_PERDAS","Conferir as perdas registadas",
                p->p.operacao().registosPerdas()>0,
                "Consulta as unidades perdidas e o número de registos conhecidos de cada produto. O total soma apenas os valores observados, pelo que meses sem registo não equivalem a perdas zero. A avaliação não distingue automaticamente as causas das perdas nem quantifica o seu custo.");
        actions.add(new Recommendation("PRESERVAR_RASTREABILIDADE","Conservar a rastreabilidade da decisão",
                "Guarda a identificação do plano, da avaliação, dos lotes e do corte. Usa o detalhe calculado para conferir cada quantidade e motivo de exclusão. As conclusões descrevem esta amostra; sem metas, custos ou informação causal, não estabelecem cumprimento de objetivos, poupanças nem causas operacionais."));
        return List.copyOf(actions);
    }
    private static void add(List<Recommendation> a,Snapshot s,String id,String title,Predicate<Produto> applies,String text) {
        var selected=s.produtos().stream().filter(applies).toList();
        if(!selected.isEmpty())a.add(new Recommendation(id,title,"Produtos abrangidos: "+codes(selected)+". "+text));
    }
    public static void validateOrder(List<Recommendation> catalog,List<String> ids) {
        var allowed=catalog.stream().map(Recommendation::id).collect(Collectors.toSet());
        if(ids==null||ids.size()!=catalog.size()||ids.stream().anyMatch(Objects::isNull)
                ||new HashSet<>(ids).size()!=ids.size()||!allowed.equals(new HashSet<>(ids)))
            throw new LocalInsightGenerator.InsightFailure("O modelo não devolveu uma priorização completa e válida. Podes tentar novamente.");
    }

    public static Analise compose(Snapshot s,List<String> ids) {
        var catalog=recommendations(s);validateOrder(catalog,ids);
        var products=s.produtos();var cells=products.stream().flatMap(p->p.celulas().stream()).toList();var c=s.contexto();
        int mae=products.stream().mapToInt(p->p.precisao().metricas().paresMae()).sum();
        int mape=products.stream().mapToInt(p->p.precisao().metricas().paresMape()).sum();
        int zero=products.stream().mapToInt(p->p.precisao().metricas().zerosExcluidosMape()).sum();
        int ops=products.stream().mapToInt(p->p.operacao().registosConhecidos()).sum();
        var sections=new ArrayList<Secao>();
        sections.add(section(0,List.of(
                "Esta análise corresponde ao plano #"+c.planoId()+", avaliação #"+c.avaliacaoId()+", de "+c.periodoInicio()+" a "+c.periodoFim()+". Abrange "+products.size()+" produto(s) e "+cells.size()+" células produto/mês. O corte de disponibilidade dos resultados é "+c.corteRealizados()+"; foram usados o lote de realizados #"+c.loteRealizadosId()+" e "+(c.loteOperacionalId()==null?"nenhum lote operacional":"o lote operacional #"+c.loteOperacionalId())+".",
                mae==0?"Não existem pares elegíveis para medir a precisão nesta avaliação. Isto impede concluir se a previsão base ou o plano aprovado ficaram mais próximos do realizado. Os produtos e as quantidades planeadas existem; os motivos concretos de exclusão são apresentados abaixo. Indicador indisponível não significa zero nem plano vazio.":"Existem "+mae+" pares elegíveis para MAE e "+mape+" para MAPE. A comparação base/aprovado usa exatamente os mesmos pares em cada produto; a cobertura e os realizados zero podem tornar as populações dos dois indicadores diferentes. Os resultados descrevem a amostra observada, sem demonstrar causalidade ou desempenho futuro.",
                "Produtos e unidades: "+codes(products)+". Todos os produtos e meses estão preservados no anexo, incluindo valores base, aprovados, realizados, métricas por horizonte e motivos de exclusão. Não se somam erros ou quantidades de produtos diferentes. Os indicadores e a explicação factual são calculados pela aplicação; a IA local apenas ordena as recomendações elegíveis da última secção.")));

        var coverage=new ArrayList<String>();
        coverage.add("A cobertura da precisão é "+percent(mae,cells.size())+": "+mae+" pares MAE em "+cells.size()+" células candidatas. Mede disponibilidade de comparação, não percentagem de acerto. Para MAPE existem "+mape+" pares e "+zero+" realizado(s) zero excluído(s); estes zeros continuam a participar no MAE quando temporalmente elegíveis.");
        coverage.add("Exclusões da precisão: "+reasonCounts(cells,false)+". Cada célula conserva o motivo determinado pelas regras de elegibilidade. Se uma regra temporal já excluiu o mês, esse motivo não permite concluir que o ficheiro de realizados está vazio ou que faltam dados noutros períodos.");
        var months=new TreeMap<String,List<Celula>>();for(var cell:cells)months.computeIfAbsent(cell.periodo(),k->new ArrayList<>()).add(cell);
        for(var month:months.entrySet())coverage.add("Mês "+month.getKey()+": "+month.getValue().size()+" célula(s), "+month.getValue().stream().filter(Celula::elegivelPrecisao).count()+" elegível(is) para precisão. Motivos de exclusão: "+reasonCounts(month.getValue(),false)+".");
        coverage.add("A avaliação é uma cópia fixa por rastreabilidade, independentemente de ter dados disponíveis. Fechar um mês ou importar novos resultados não altera esta avaliação: é necessário criar outra com lotes e corte adequados. Uma publicação tardia permanece tardia na célula afetada, inclusive numa nova avaliação; não é corrigida por novos dados ou por esperar.");
        sections.add(section(1,coverage));

        var precision=new ArrayList<String>();
        precision.add("O MAE é a média dos erros absolutos e mantém a unidade do produto: quanto menor, menor o erro médio observado. O MAPE é a média dos erros percentuais absolutos sobre realizados não nulos e diferentes de zero. Compara base e aprovado dentro de cada produto e sobre os mesmos pares; não há MAE global que misture produtos ou unidades.");
        precision.add("O ganho do ajuste é MAE base menos MAE aprovado. Um valor positivo corresponde a melhoria observada, um valor negativo a agravamento e zero a igualdade à precisão apresentada. Sem pares elegíveis, MAE, MAPE e ganho ficam indisponíveis. A precisão depende dos realizados e das regras temporais; a ausência de registos operacionais não impede o seu cálculo.");
        for(var p:products.stream().limit(SUMMARY_PRODUCTS).toList()) {
            var m=p.precisao().metricas();precision.add(product(p)+": "+m.paresMae()+"/"+m.candidatos()+" pares MAE; cobertura "+value(m.coberturaPercentagem(),"%")+". MAE base "+value(m.maeBase(),p.precisao().unidade())+", aprovado "+value(m.maeAprovado(),p.precisao().unidade())+"; ganho "+value(m.ganhoAjuste(),p.precisao().unidade())+". MAPE base "+value(m.mapeBase(),"%")+", aprovado "+value(m.mapeAprovado(),"%")+", com "+m.paresMape()+" pares e "+m.zerosExcluidosMape()+" zero(s) excluído(s). "+gainMeaning(m));
        }
        addRemainder(precision,products.size());sections.add(section(2,precision));

        var operation=new ArrayList<String>();
        operation.add("Existem "+ops+" registos operacionais elegíveis conhecidos em "+cells.size()+" células candidatas. A cobertura operacional é independente da cobertura da precisão: serviço, rutura, excesso de stock e perdas podem ter denominadores e campos disponíveis diferentes, mesmo para o mesmo produto e mês. Motivos operacionais: "+reasonCounts(cells,true)+".");
        operation.add("O nível de serviço divide unidades satisfeitas no prazo por unidades solicitadas. A taxa de rutura divide dias com procura sem stock por dias observados com procura. Os rácios são ponderados pelos denominadores, sem média simples das percentagens mensais; um denominador zero deixa o rácio indisponível. O excesso de stock é a média da diferença positiva entre stock de fecho e limite, e as perdas são a soma das unidades perdidas conhecidas.");
        for(var p:products.stream().limit(SUMMARY_PRODUCTS).toList()) {
            var o=p.operacao();operation.add(product(p)+": "+o.registosConhecidos()+" registo(s) operacional(is) conhecido(s) em "+o.candidatos()+" células. Nível de serviço "+value(o.nivelServicoPercentagem(),"%")+" ("+o.paresServico()+" pares); rutura "+value(o.ruturaPercentagem(),"%")+" ("+o.paresRutura()+" pares de dias). Excesso médio "+value(o.excessoStockMedio(),p.precisao().unidade())+" ("+o.paresStock()+" células com stock e limite); perdas totais "+value(o.unidadesPerdidasTotal(),p.precisao().unidade())+" ("+o.registosPerdas()+" registos). Estes valores não estabelecem uma causa nem cumprimento de metas que não foram fornecidas.");
        }
        addRemainder(operation,products.size());sections.add(section(3,operation));

        var priorities=new ArrayList<String>();
        priorities.add("As prioridades abaixo identificam condições verificáveis por produto, sem classificar artificialmente produtos sem resultados. Primeiro distinguem-se limitações de dados e agravamentos observados dos ajustes; não se usa o MAE absoluto para ordenar produtos com escalas ou unidades diferentes. O detalhe integral permite verificar a população e cada mês antes de agir.");
        var prioritized=products.stream().sorted(Comparator.comparingInt(GroundedInsightReport::attention).thenComparing(p->p.precisao().produto())).toList();
        for(var p:prioritized.stream().limit(SUMMARY_PRODUCTS).toList()) {
            var m=p.precisao().metricas();var o=p.operacao();
            priorities.add(product(p)+": "+(m.paresMae()==0?"preparar uma comparação elegível, pois não existem pares MAE; não é possível classificar o desempenho da previsão":"conferir "+m.paresMae()+" pares MAE e o efeito do ajuste: "+gainMeaning(m))+". Exclusões da precisão: "+reasonCounts(p.celulas(),false)+". Operação: "+o.registosConhecidos()+"/"+o.candidatos()+" registos conhecidos; "+(o.registosConhecidos()==0?"verificar os motivos operacionais sem os confundir com a precisão da previsão":"conferir os denominadores de serviço/rutura e a cobertura de stock/perdas no anexo")+".");
        }
        addRemainder(priorities,products.size());
        priorities.add("Sem metas, custos, margens ou contexto causal não se conclui que exista um nível aceitável de serviço, uma poupança, uma causa de rutura ou uma recomendação ótima de compras. As ações são verificações sustentadas pelos dados desta avaliação, mantendo a decisão e a justificação com a equipa responsável.");
        sections.add(section(4,priorities));

        var recommendations=new ArrayList<String>();
        recommendations.add("A IA local priorizou as ações seguintes entre recomendações cuja aplicabilidade foi verificada pela aplicação. A IA não escreveu as conclusões quantitativas nem alterou valores, motivos de exclusão ou regras de elegibilidade. Todas as ações elegíveis foram mantidas, sem omissões; a ordem é uma sugestão para apoiar a equipa e não uma decisão automática.");
        var byId=catalog.stream().collect(Collectors.toMap(Recommendation::id,r->r));
        for(int i=0;i<ids.size();i++){var r=byId.get(ids.get(i));recommendations.add((i+1)+". "+r.titulo()+". "+r.texto());}
        sections.add(section(5,recommendations));
        return new Analise("Análise dos resultados do plano #"+c.planoId(),List.copyOf(sections));
    }
    private static Secao section(int n,List<String> paragraphs){return new Secao(InsightPrompt.SECTIONS.get(n),List.copyOf(paragraphs));}
    private static void addRemainder(List<String> paragraphs,int count){if(count>SUMMARY_PRODUCTS)paragraphs.add("O resumo apresenta "+SUMMARY_PRODUCTS+" produtos; os restantes "+(count-SUMMARY_PRODUCTS)+" e todos os meses constam no anexo calculado, sem omissões. Usa o seletor Produto a conferir para consultar os valores individuais e as métricas por horizonte.");}
    private static int attention(Produto p){var m=p.precisao().metricas();return m.paresMae()==0?0:negative(m.ganhoAjuste())?1:2;}
    private static String gainMeaning(Metricas m){if(m.ganhoAjuste()==null)return "Ganho indisponível por falta de pares elegíveis.";int sign=new BigDecimal(m.ganhoAjuste()).signum();return sign>0?"O ajuste reduziu o MAE nesta amostra.":sign<0?"O ajuste aumentou o MAE nesta amostra.":"O MAE base e aprovado é igual à precisão apresentada.";}
    private static boolean negative(String n){return n!=null&&new BigDecimal(n).signum()<0;}
    private static String value(String n,String unit){return n==null?"indisponível":n.replace('.',',')+(unit.equals("%")?"%":" "+unit);}
    private static String product(Produto p){return p.precisao().produto()+" ("+p.precisao().unidade()+")";}
    private static String codes(List<Produto> p){return p.stream().map(GroundedInsightReport::product).collect(Collectors.joining(", "));}
    private static String percent(int n,int total){return value(MonitoringMetrics.ratio(BigDecimal.valueOf(n),BigDecimal.valueOf(total)),"%");}
    private static String reasonCounts(List<Celula> cells,boolean operational){
        var counts=new LinkedHashMap<String,Integer>();
        for(var c:cells){String reason=operational?c.motivoOperacional():c.motivoExclusao();if(reason!=null)counts.merge(reason,1,Integer::sum);}
        if(counts.isEmpty())return "nenhuma";
        return counts.entrySet().stream().map(e->reason(e.getKey())+" = "+e.getValue()).collect(Collectors.joining("; "));
    }
    private static String reason(String code){return switch(code){
        case "PLANO_PUBLICADO_TARDIAMENTE"->"publicação tardia";
        case "PERIODO_NAO_FECHADO"->"mês ainda não fechado no corte";
        case "REALIZADO_APOS_CORTE"->"realizado conhecido após o corte";
        case "SEM_REALIZADO"->"realizado em falta";
        case "SEM_LOTE_OPERACIONAL"->"sem lote operacional";
        case "SEM_REGISTO_OPERACIONAL"->"sem registo operacional para o produto/mês";
        case "OPERACIONAL_APOS_CORTE"->"registo operacional disponível após o corte";
        default->code;};}
}
