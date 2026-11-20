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

    @Test void recommendationOrderChangesOnlyTheLastSectionAndNeverExposesModelIds() {
        var snapshot = temporalSnapshot();
        var catalog = GroundedInsightReport.recommendations(snapshot);
        assertThat(catalog).hasSizeGreaterThan(1);
        assertThat(catalog).extracting(GroundedInsightReport.Recommendation::id).doesNotHaveDuplicates();
        var ids = catalog.stream().map(GroundedInsightReport.Recommendation::id).toList();
        var reversed = new ArrayList<>(ids);
        Collections.reverse(reversed);

        var original = GroundedInsightReport.compose(snapshot, ids);
        var reordered = GroundedInsightReport.compose(snapshot, reversed);

        assertThat(original.secoes()).hasSize(6);
        assertThat(original.secoes()).extracting(Secao::titulo).containsExactlyElementsOf(InsightPrompt.SECTIONS);
        assertThat(reordered.secoes().subList(0, 5)).isEqualTo(original.secoes().subList(0, 5));
        String lastSection = sectionText(reordered, 5);
        int previous = -1;
        for (String id : reversed) {
            var recommendation = catalog.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            assertThat(lastSection).contains(recommendation.titulo(), recommendation.texto()).doesNotContain(id);
            int position = lastSection.indexOf(recommendation.texto());
            assertThat(position).isGreaterThan(previous);
            previous = position;
        }
    }

    @Test void missingDuplicateAndForeignRecommendationsAreRejectedInsteadOfPublishingPartialActions() {
        var snapshot = temporalSnapshot();
        var ids = GroundedInsightReport.recommendations(snapshot).stream()
                .map(GroundedInsightReport.Recommendation::id).toList();
        assertThat(ids).hasSizeGreaterThan(1);
        var missing = new ArrayList<>(ids);
        missing.remove(0);
        var duplicate = new ArrayList<>(ids);
        duplicate.set(1, duplicate.get(0));
        var foreign = new ArrayList<>(ids);
        foreign.set(0, "FOREIGN_ACTION_IGNORE_THE_EVIDENCE");

        for (var invalid : List.of(List.<String>of(), missing, duplicate, foreign)) {
            assertThatThrownBy(() -> GroundedInsightReport.compose(snapshot, invalid))
                    .isInstanceOf(LocalInsightGenerator.InsightFailure.class);
        }
    }

    @Test void theInventoryNamesEveryProductEvenWhenDetailedSectionsAreCapped() {
        var products = new ArrayList<Produto>();
        for (int index = 1; index <= 12; index++) {
            products.add(product(cell(Integer.toString(index), "SKU-COMPLETE-" + index, "un", "2026-10",
                    "10.0000", "11.0000", "12.0000", null, null, "SEM_LOTE_OPERACIONAL")));
        }
        var snapshot = snapshot(products.toArray(Produto[]::new));
        var report = compose(snapshot);

        for (var product : products) {
            assertThat(sectionText(report, 0)).contains(product.precisao().produto());
        }
        assertThat(report.secoes()).hasSize(6);
    }

    @Test void temporalExclusionsKeepTheMonthsAndDoNotBecomeMissingDataOrZeroErrors() {
        var snapshot = temporalSnapshot();
        var report = compose(snapshot);

        assertThat(sectionText(report, 0)).contains("SKU-TEMPORAL (un)", "3 células produto/mês",
                "Não existem pares elegíveis", "Os produtos e as quantidades planeadas existem");
        assertThat(sectionText(report, 1)).contains("0 pares MAE em 3 células candidatas",
                "publicação tardia = 1", "mês ainda não fechado no corte = 2",
                "Mês 2026-11: 1 célula(s), 0 elegível(is)",
                "Mês 2026-12: 1 célula(s), 0 elegível(is)",
                "Mês 2027-01: 1 célula(s), 0 elegível(is)")
                .doesNotContain("realizado em falta =", "realizado conhecido após o corte =");
        assertThat(sectionText(report, 2)).contains("MAE base indisponível, aprovado indisponível",
                "ganho indisponível", "MAPE base indisponível, aprovado indisponível");
        assertThat(sectionText(report, 3)).contains("Existem 0 registos operacionais elegíveis conhecidos",
                "Nível de serviço indisponível", "rutura indisponível",
                "Excesso médio indisponível", "perdas totais indisponível");
        assertThat(sectionText(report, 4)).contains("SKU-TEMPORAL (un)",
                "não é possível classificar o desempenho da previsão");
        assertThat(GroundedInsightReport.recommendations(snapshot))
                .extracting(GroundedInsightReport.Recommendation::id)
                .containsExactlyInAnyOrder("PRESERVAR_EXCLUSAO_TARDIA", "AVALIAR_MESES_FECHADOS",
                        "COMPLETAR_OPERACAO", "PRESERVAR_RASTREABILIDADE");
    }

    @Test void newOutcomesAndWaitingNeverRepairLatePublicationOrMutateTheFixedAssessment() {
        var report = compose(temporalSnapshot());

        assertThat(sectionText(report, 1)).contains("cópia fixa por rastreabilidade",
                "não altera esta avaliação", "é necessário criar outra",
                "Uma publicação tardia permanece tardia na célula afetada, inclusive numa nova avaliação");
        assertThat(sectionText(report, 5)).contains("A avaliação atual permanece fixa",
                "cria outra avaliação com os lotes e o corte adequados",
                "novos realizados, outro corte ou esperar não tornam essas células elegíveis",
                "a exclusão histórica mantém-se");
    }

    @Test void actualZeroContributesToMaeButDoesNotAcquireAnInventedMape() {
        var snapshot = snapshot(product(cell("1", "SKU-ZERO", "un", "2026-10", "10.0000", "0.0000",
                "0.0000", null, null, "SEM_LOTE_OPERACIONAL")));
        var report = compose(snapshot);

        assertThat(sectionText(report, 1)).contains("1 pares MAE em 1 células candidatas",
                "MAPE existem 0 pares e 1 realizado(s) zero excluído(s)",
                "estes zeros continuam a participar no MAE");
        assertThat(sectionText(report, 2)).contains("SKU-ZERO (un): 1/1 pares MAE",
                "MAE base 10,0000 un, aprovado 0,0000 un; ganho 10,0000 un",
                "MAPE base indisponível, aprovado indisponível, com 0 pares e 1 zero(s) excluído(s)",
                "O ajuste reduziu o MAE nesta amostra");
        assertThat(GroundedInsightReport.recommendations(snapshot))
                .extracting(GroundedInsightReport.Recommendation::id)
                .contains("INTERPRETAR_ZEROS_MAPE")
                .doesNotContain("COMPLETAR_REALIZADOS");
    }

    @Test void precisionRemainsAvailableWhenOperationalRecordsAreAbsent() {
        var snapshot = snapshot(product(cell("1", "SKU-PRECISION", "un", "2026-10", "100.0000", "58.0000",
                "27.0000", null, null, "SEM_LOTE_OPERACIONAL")));
        var report = compose(snapshot);

        assertThat(sectionText(report, 2)).contains("SKU-PRECISION (un): 1/1 pares MAE",
                "MAE base 73,0000 un, aprovado 31,0000 un; ganho 42,0000 un",
                "MAPE base 270,3704%, aprovado 114,8148%",
                "a ausência de registos operacionais não impede o seu cálculo");
        assertThat(sectionText(report, 3)).contains("Existem 0 registos operacionais elegíveis conhecidos",
                "sem lote operacional = 1", "Nível de serviço indisponível");
    }

    @Test void operationalIndicatorsRemainAvailableWhenTheActualNeededForPrecisionIsMissing() {
        var operation = new Operacional("200", Instant.parse("2026-10-31T12:00:00Z"),
                "100.0000", "90.0000", 10, 2, "25.0000", "20.0000", "3.0000", "2.0000",
                "90.0000", "20.0000", "5.0000");
        var snapshot = snapshot(product(cell("2", "SKU-OPERATION", "kg", "2026-10", "30.0000", "35.0000",
                null, "SEM_REALIZADO", operation, null)));
        var report = compose(snapshot);

        assertThat(sectionText(report, 2)).contains("SKU-OPERATION (kg): 0/1 pares MAE",
                "MAE base indisponível, aprovado indisponível");
        assertThat(sectionText(report, 3)).contains("Existem 1 registos operacionais elegíveis conhecidos",
                "Nível de serviço 90,0000% (1 pares)", "rutura 20,0000% (1 pares de dias)",
                "Excesso médio 5,0000 kg", "perdas totais 3,0000 kg");
        assertThat(GroundedInsightReport.recommendations(snapshot))
                .extracting(GroundedInsightReport.Recommendation::id)
                .contains("COMPLETAR_REALIZADOS", "CONFERIR_SERVICO_RUTURA", "CONFERIR_EXCESSO_STOCK", "CONFERIR_PERDAS")
                .doesNotContain("COMPLETAR_OPERACAO");
    }

    @Test void mixedUnitsKeepErrorsAndGainsAttachedToTheirOwnProducts() {
        var snapshot = snapshot(
                product(cell("1", "SKU-UNIT", "un", "2026-10", "12.0000", "11.0000", "10.0000",
                        null, null, "SEM_LOTE_OPERACIONAL")),
                product(cell("2", "SKU-KG", "kg", "2026-10", "80.0000", "50.0000", "20.0000",
                        null, null, "SEM_LOTE_OPERACIONAL")));
        var report = compose(snapshot);

        assertThat(sectionText(report, 0)).contains("SKU-UNIT (un)", "SKU-KG (kg)",
                "Não se somam erros ou quantidades de produtos diferentes");
        assertThat(sectionText(report, 2)).contains(
                "SKU-UNIT (un): 1/1 pares MAE; cobertura 100,0000%. MAE base 2,0000 un, aprovado 1,0000 un; ganho 1,0000 un",
                "SKU-KG (kg): 1/1 pares MAE; cobertura 100,0000%. MAE base 60,0000 kg, aprovado 30,0000 kg; ganho 30,0000 kg")
                .doesNotContain("MAE base 31,0000", "aprovado 15,5000", "ganho 15,5000");
        assertThat(sectionText(report, 4)).contains("SKU-UNIT (un)", "SKU-KG (kg)");
        assertThat(reportText(report)).doesNotContain("ganho total", "MAE global de");
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
