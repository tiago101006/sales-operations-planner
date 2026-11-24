package pt.tiagodias.sop.monitoring;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import static pt.tiagodias.sop.monitoring.InsightDtos.*;
import static pt.tiagodias.sop.monitoring.MonitoringDtos.*;

/** All claims and quantities come from the immutable assessment; AI may only order eligible actions. */
public final class GroundedInsightReport {
    private static final int SUMMARY_PRODUCTS = 2;
    private static final int SUMMARY_ACTIONS = 3;
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
        int zero=products.stream().mapToInt(p->p.precisao().metricas().zerosExcluidosMape()).sum();
        int ops=products.stream().mapToInt(p->p.operacao().registosConhecidos()).sum();
        var sections=new ArrayList<Secao>();
        var summary=new ArrayList<String>();
        summary.add("Plano #"+c.planoId()+" · "+c.periodoInicio()+" a "+c.periodoFim()+" · "+products.size()+" produto(s). Cobertura da precisão: "+percent(mae,cells.size())+" ("+mae+"/"+cells.size()+" pares elegíveis). Cobertura mede dados comparáveis, não acerto.");
        if(mae<cells.size())summary.add("Exclusões: "+reasonCounts(cells,false)+". Indisponível não significa zero nem plano vazio.");
        sections.add(section(0,summary));

        var precision=new ArrayList<String>();
        long improved=products.stream().filter(p->sign(p.precisao().metricas().ganhoAjuste())>0).count();
        long worsened=products.stream().filter(p->negative(p.precisao().metricas().ganhoAjuste())).count();
        long equal=products.stream().filter(p->"0.0000".equals(p.precisao().metricas().ganhoAjuste())).count();
        precision.add(mae==0?"MAE, MAPE e ganho indisponíveis: não existem pares elegíveis para comparar a previsão com o realizado."
                :"Nos produtos avaliáveis, o ajuste reduziu o MAE em "+improved+", aumentou em "+worsened+" e manteve-o em "+equal+". Compara-se sempre nos mesmos pares, por produto.");
        for(var p:products.stream().filter(v->v.precisao().metricas().paresMae()>0)
                .sorted(Comparator.comparingInt((Produto v)->negative(v.precisao().metricas().ganhoAjuste())?0:1)
                        .thenComparing(v->v.precisao().produto())).limit(SUMMARY_PRODUCTS).toList()) {
            var m=p.precisao().metricas();
            precision.add(product(p)+": MAE base "+value(m.maeBase(),p.precisao().unidade())+", aprovado "+value(m.maeAprovado(),p.precisao().unidade())+"; MAPE base "+value(m.mapeBase(),"%")+", aprovado "+value(m.mapeAprovado(),"%")+".");
        }
        if(zero>0)precision.add(zero+" realizado(s) zero excluído(s) apenas do MAPE; continuam no MAE.");
        sections.add(section(1,precision));

        var operation=new ArrayList<String>();
        operation.add(ops==0?"Indicadores operacionais indisponíveis: nenhum registo elegível conhecido nesta avaliação. Isto não impede calcular a precisão quando existem realizados elegíveis."
                :ops+"/"+cells.size()+" registos operacionais conhecidos; serviço, rutura, stock e perdas podem ter coberturas diferentes.");
        products.stream().filter(p->p.operacao().registosConhecidos()>0).sorted(Comparator.comparing(p->p.precisao().produto())).findFirst().ifPresent(p->{
            var o=p.operacao();operation.add(product(p)+": serviço "+value(o.nivelServicoPercentagem(),"%")+"; rutura "+value(o.ruturaPercentagem(),"%")+"; excesso médio "+value(o.excessoStockMedio(),p.precisao().unidade())+"; perdas "+value(o.unidadesPerdidasTotal(),p.precisao().unidade())+".");
        });
        sections.add(section(2,operation));

        var actions=new ArrayList<String>();
        for(int i=0;i<Math.min(SUMMARY_ACTIONS,ids.size());i++)actions.add((i+1)+". "+shortAction(ids.get(i)));
        actions.add("Destaques selecionados; todos os produtos, meses e indicadores permanecem em Conferir os dados. Sem metas ou causas fornecidas, estes resultados não comprovam objetivos nem causalidade.");
        sections.add(section(3,actions));
        return new Analise("Resumo dos resultados do plano #"+c.planoId(),List.copyOf(sections));
    }
    private static Secao section(int n,List<String> paragraphs){return new Secao(InsightPrompt.SECTIONS.get(n),List.copyOf(paragraphs));}
    private static int sign(String n){return n==null?0:new BigDecimal(n).signum();}
    private static String shortAction(String id){return switch(id){
        case "PRESERVAR_EXCLUSAO_TARDIA"->"Publica planos futuros antes do início do mês. Esperar ou importar resultados não corrige uma publicação tardia; a exclusão histórica mantém-se.";
        case "AVALIAR_MESES_FECHADOS"->"Cria outra avaliação quando os meses fecharem, com lotes e corte adequados. A avaliação atual fica fixa; as restantes regras continuam aplicáveis.";
        case "COMPLETAR_REALIZADOS"->"Importa os realizados em falta num lote validado e cria outra avaliação. Não substituas valores desconhecidos por zero.";
        case "REVER_CORTE_REALIZADOS"->"Confere a disponibilidade dos realizados. Para os incluir, cria outra avaliação com um corte adequado, preservando as datas originais.";
        case "INTERPRETAR_ZEROS_MAPE"->"Usa o MAE para os realizados zero e confere os pares de cada indicador. MAPE indisponível não significa erro nulo.";
        case "REVER_AJUSTES_COM_PIOR_MAE"->"Revê os produtos cujo ajuste aumentou o MAE e consulta as justificações antes de alterar planos futuros.";
        case "ACOMPANHAR_AJUSTES_AVALIAVEIS"->"Acompanha o ganho dos ajustes em novas avaliações, nos mesmos pares elegíveis. Uma melhoria nesta amostra não garante resultados futuros.";
        case "COMPLETAR_OPERACAO"->"Confere os motivos de exclusão operacional e reúne os registos aplicáveis antes de criar outra avaliação.";
        case "CONFERIR_SERVICO_RUTURA"->"Confere serviço e rutura com os respetivos denominadores. Sem metas definidas, não classifiques os valores como aceitáveis ou insuficientes.";
        case "CONFERIR_EXCESSO_STOCK"->"Compara o stock de fecho com os limites de cada mês. O excesso observado não explica a sua causa nem o custo.";
        case "CONFERIR_PERDAS"->"Confere as perdas e a cobertura por produto. Meses sem registo não equivalem a perdas zero.";
        case "PRESERVAR_RASTREABILIDADE"->"Conserva o plano, a avaliação, os lotes e o corte. Confere os dados antes de decidir.";
        default->throw new IllegalArgumentException("Recomendação desconhecida.");};}
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
