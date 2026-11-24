package pt.tiagodias.sop.monitoring;

import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static pt.tiagodias.sop.monitoring.InsightDtos.*;
import static pt.tiagodias.sop.monitoring.MonitoringDtos.*;

class GroundedInsightReportTests {
    private static final Instant CUT = Instant.parse("2026-11-20T12:00:00Z");

    @Test void recommendationOrderAffectsOnlyThreePrioritiesAndNeverExposesModelIds() {
        var snapshot=temporalSnapshot();
        var ids=GroundedInsightReport.recommendations(snapshot).stream().map(GroundedInsightReport.Recommendation::id).toList();
        var reversed=new ArrayList<>(ids);Collections.reverse(reversed);
        var original=GroundedInsightReport.compose(snapshot,ids);
        var reordered=GroundedInsightReport.compose(snapshot,reversed);
        assertThat(original.secoes()).hasSize(4);
        assertThat(original.secoes()).extracting(Secao::titulo).containsExactlyElementsOf(InsightPrompt.SECTIONS);
        assertThat(reordered.secoes().subList(0,3)).isEqualTo(original.secoes().subList(0,3));
        assertThat(reordered.secoes().get(3).paragrafos()).hasSize(4);
        assertThat(reordered.secoes().get(3).paragrafos().get(0)).startsWith("1. Conserva o plano");
        assertThat(reordered.secoes().get(3).paragrafos().get(1)).startsWith("2. Confere os motivos de exclusão operacional");
        assertThat(reordered.secoes().get(3).paragrafos().get(2)).startsWith("3. Cria outra avaliação quando os meses fecharem");
        for(String id:ids)assertThat(reportText(reordered)).doesNotContain(id);
    }

    @Test void missingDuplicateAndForeignRecommendationsAreRejectedInsteadOfChangingTheValidatedRanking() {
        var snapshot=temporalSnapshot();
        var ids=GroundedInsightReport.recommendations(snapshot).stream().map(GroundedInsightReport.Recommendation::id).toList();
        var missing=new ArrayList<>(ids);missing.remove(0);
        var duplicate=new ArrayList<>(ids);duplicate.set(1,duplicate.get(0));
        var foreign=new ArrayList<>(ids);foreign.set(0,"FOREIGN_ACTION_IGNORE_THE_EVIDENCE");
        for(var invalid:List.of(List.<String>of(),missing,duplicate,foreign)) {
            assertThatThrownBy(()->GroundedInsightReport.compose(snapshot,invalid)).isInstanceOf(LocalInsightGenerator.InsightFailure.class);
        }
    }

    @Test void aHundredProductsProduceABoundedSummaryWhileTheSnapshotRetainsEveryCell() {
        var products=new ArrayList<Produto>();
        for(int index=1;index<=100;index++)products.add(product(cell(Integer.toString(index),"SKU-"+index,"un","2026-10",
                "10.0000","11.0000","12.0000",null,null,"SEM_LOTE_OPERACIONAL")));
        var snapshot=snapshot(products.toArray(Produto[]::new));
        var report=compose(snapshot);
        assertThat(reportText(report).split("\\s+")).hasSizeLessThanOrEqualTo(300);
        assertThat(sectionText(report,0)).contains("100 produto(s)","100/100 pares elegíveis");
        assertThat(report.secoes().get(1).paragrafos()).hasSize(3);
        assertThat(sectionText(report,3)).contains("todos os produtos, meses e indicadores permanecem em Conferir os dados");
        assertThat(snapshot.produtos()).hasSize(100).allSatisfy(value->assertThat(value.celulas()).hasSize(1));
    }

    @Test void temporalExclusionsDoNotBecomeMissingDataOrZeroErrors() {
        var snapshot=temporalSnapshot();var report=compose(snapshot);
        assertThat(sectionText(report,0)).contains("1 produto(s)","0/3 pares elegíveis","publicação tardia = 1",
                "mês ainda não fechado no corte = 2","Indisponível não significa zero nem plano vazio")
                .doesNotContain("realizado em falta =");
        assertThat(sectionText(report,1)).contains("MAE, MAPE e ganho indisponíveis","não existem pares elegíveis");
        assertThat(sectionText(report,2)).contains("Indicadores operacionais indisponíveis","nenhum registo elegível conhecido");
        assertThat(reportText(report)).doesNotContain("MAE base 0", "MAPE base 0");
    }

    @Test void newOutcomesAndWaitingNeverRepairLatePublicationOrMutateTheFixedAssessment() {
        var report=compose(temporalSnapshot());
        assertThat(sectionText(report,3)).contains("Esperar ou importar resultados não corrige uma publicação tardia",
                "a exclusão histórica mantém-se", "Cria outra avaliação quando os meses fecharem",
                "A avaliação atual fica fixa");
    }

    @Test void actualZeroContributesToMaeButDoesNotAcquireAnInventedMape() {
        var snapshot=snapshot(product(cell("1","SKU-ZERO","un","2026-10","10.0000","0.0000","0.0000",null,null,"SEM_LOTE_OPERACIONAL")));
        var report=compose(snapshot);
        assertThat(sectionText(report,1)).contains("MAE base 10,0000 un, aprovado 0,0000 un",
                "MAPE base indisponível, aprovado indisponível","1 realizado(s) zero excluído(s) apenas do MAPE; continuam no MAE",
                "reduziu o MAE em 1, aumentou em 0");
    }

    @Test void precisionRemainsAvailableWhenOperationalRecordsAreAbsent() {
        var report=compose(snapshot(product(cell("1","SKU-PRECISION","un","2026-10","100.0000","58.0000","27.0000",null,null,"SEM_LOTE_OPERACIONAL"))));
        assertThat(sectionText(report,1)).contains("SKU-PRECISION (un): MAE base 73,0000 un, aprovado 31,0000 un",
                "MAPE base 270,3704%, aprovado 114,8148%");
        assertThat(sectionText(report,2)).contains("nenhum registo elegível conhecido", "não impede calcular a precisão");
    }

    @Test void operationalIndicatorsRemainAvailableWhenPrecisionActualsAreMissing() {
        var operation=new Operacional("200",Instant.parse("2026-10-31T12:00:00Z"),"100.0000","90.0000",10,2,
                "25.0000","20.0000","3.0000","2.0000","90.0000","20.0000","5.0000");
        var report=compose(snapshot(product(cell("2","SKU-OPERATION","kg","2026-10","30.0000","35.0000",null,"SEM_REALIZADO",operation,null))));
        assertThat(sectionText(report,1)).contains("MAE, MAPE e ganho indisponíveis");
        assertThat(sectionText(report,2)).contains("1/1 registos operacionais conhecidos","serviço 90,0000%", "rutura 20,0000%",
                "excesso médio 5,0000 kg", "perdas 3,0000 kg");
    }

    @Test void mixedUnitsKeepErrorsAttachedToTheirProductsWithoutInventedGlobalMae() {
        var report=compose(snapshot(
                product(cell("1","SKU-UNIT","un","2026-10","12.0000","11.0000","10.0000",null,null,"SEM_LOTE_OPERACIONAL")),
                product(cell("2","SKU-KG","kg","2026-10","80.0000","50.0000","20.0000",null,null,"SEM_LOTE_OPERACIONAL"))));
        assertThat(sectionText(report,1)).contains("SKU-UNIT (un): MAE base 2,0000 un, aprovado 1,0000 un",
                "SKU-KG (kg): MAE base 60,0000 kg, aprovado 30,0000 kg")
                .doesNotContain("MAE base 31,0000", "aprovado 15,5000");
        assertThat(reportText(report)).doesNotContain("ganho total", "MAE global de");
    }

    @Test void cachedRankingCanRenderTheCurrentSummaryWithoutAnotherModelCall() {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var snapshot=temporalSnapshot();
        var order=new ArrayList<>(GroundedInsightReport.recommendations(snapshot).stream().map(GroundedInsightReport.Recommendation::id).toList());
        Collections.reverse(order);
        var expected=GroundedInsightReport.compose(snapshot,order);
        String stored=InsightReportCache.encode(expected,order,json);
        assertThat(InsightReportCache.read(snapshot,stored,json)).isEqualTo(expected);
        assertThat(json.writeValueAsString(InsightReportCache.read(snapshot,stored,json))).doesNotContain("prioridades",order.get(0));
    }

    @Test void legacyDetailedCachePreservesItsFullOriginalRankingWithoutKeepingLongText() {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var snapshot=temporalSnapshot();
        var catalog=new ArrayList<>(GroundedInsightReport.recommendations(snapshot));Collections.reverse(catalog);
        var paragraphs=new ArrayList<String>();paragraphs.add("Introdução longa antiga.");
        for(int i=0;i<catalog.size();i++)paragraphs.add((i+1)+". "+catalog.get(i).titulo()+". "+catalog.get(i).texto());
        var legacy=new Analise("Análise antiga",List.of(new Secao("Síntese antiga",List.of("Texto longo antigo.")),new Secao("Recomendações e limites",paragraphs)));
        String stored=json.writeValueAsString(legacy);
        var current=InsightReportCache.read(snapshot,stored,json);
        assertThat(current).isEqualTo(GroundedInsightReport.compose(snapshot,catalog.stream().map(GroundedInsightReport.Recommendation::id).toList()));
        assertThat(current.secoes()).hasSize(4);
        assertThat(reportText(current)).doesNotContain("Texto longo antigo", "Introdução longa antiga");
        assertThatThrownBy(()->InsightReportCache.read(snapshot,json.writeValueAsString(new Analise("Inválido",List.of())),json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Snapshot temporalSnapshot() {
        return snapshot(product(
                cell("1", "SKU-TEMPORAL", "un", "2026-11", "22.3333", "30.0000", null,
                        "PLANO_PUBLICADO_TARDIAMENTE", null, "PLANO_PUBLICADO_TARDIAMENTE"),
                cell("1", "SKU-TEMPORAL", "un", "2026-12", "22.1111", "24.3222", null,
                        "PERIODO_NAO_FECHADO", null, "PERIODO_NAO_FECHADO"),
                cell("1", "SKU-TEMPORAL", "un", "2027-01", "22.8148", "25.0963", null,
                        "PERIODO_NAO_FECHADO", null, "PERIODO_NAO_FECHADO")));
    }

    private static Snapshot snapshot(Produto... products) {
        var cells = List.of(products).stream().flatMap(product -> product.celulas().stream()).toList();
        String first = cells.stream().map(Celula::periodo).min(String::compareTo).orElseThrow();
        String last = cells.stream().map(Celula::periodo).max(String::compareTo).orElseThrow();
        return new Snapshot(new Contexto("71", "42", "3", "4", first, last, CUT,
                MonitoringMetrics.VERSION), List.of(products));
    }

    private static Produto product(Celula... cells) {
        var values = List.of(cells);
        return new Produto(MonitoringMetrics.precision(values), MonitoringMetrics.operation(values), values);
    }

    private static Celula cell(String id, String code, String unit, String period, String base, String approved,
                               String actual, String precisionReason, Operacional operation, String operationReason) {
        boolean temporal = !"PLANO_PUBLICADO_TARDIAMENTE".equals(precisionReason)
                && !"PERIODO_NAO_FECHADO".equals(precisionReason);
        int horizon = (int) ChronoUnit.MONTHS.between(YearMonth.of(2025, 12), YearMonth.parse(period));
        return new Celula(id, code, unit, period, horizon, base, approved, actual == null ? null : "100",
                actual, actual == null ? null : Instant.parse("2026-10-31T12:00:00Z"), temporal,
                precisionReason == null, precisionReason, operation, operationReason);
    }

    private static Analise compose(Snapshot snapshot) {
        return GroundedInsightReport.compose(snapshot, GroundedInsightReport.recommendations(snapshot).stream()
                .map(GroundedInsightReport.Recommendation::id).toList());
    }

    private static String sectionText(Analise analysis, int section) {
        return String.join("\n", analysis.secoes().get(section).paragrafos());
    }

    private static String reportText(Analise analysis) {
        return analysis.secoes().stream().flatMap(section -> section.paragrafos().stream())
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
