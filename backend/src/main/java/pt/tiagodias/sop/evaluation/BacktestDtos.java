package pt.tiagodias.sop.evaluation;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;
import pt.tiagodias.sop.imports.ImportDtos.TipoSerie;
import tools.jackson.databind.JsonNode;

public final class BacktestDtos {
    private BacktestDtos() {}
    public record CreateBacktest(@NotNull @Positive Long loteHistoricoId, @NotNull TipoSerie tipoSerie,
            @NotBlank @Pattern(regexp="[1-9][0-9]{3}-(0[1-9]|1[0-2])") String primeiroCorte,
            @NotBlank @Pattern(regexp="[1-9][0-9]{3}-(0[1-9]|1[0-2])") String ultimoCorte,
            @NotNull @Min(1) @Max(12) Integer horizonteMeses,
            @NotNull @Min(1) @Max(36) Integer janelaMediaMovel,
            @NotNull @Min(6) @Max(120) Integer historicoEtsMeses,
            Instant corteRealizados) {}
    public record BacktestDto(String id, String loteHistoricoId, String loteSha256, String tipoSerie, String modo,
            String primeiroCorte, String ultimoCorte, int horizonteMeses, Instant corteRealizados,
            JsonNode parametros, String versaoMediaMovel, String versaoEts, String versaoCalculo,
            String estado, String criadoPor, Instant criadoEm, Instant concluidoEm, long totalCelulas) {}
    public record CelulaDto(String produtoId, String produto, String unidade, String corte,
            Instant instanteCorte, String periodo, int horizonte, String observacaoId,
            String realizado, String previsaoMediaMovel, String previsaoEts,
            String estadoMediaMovel, String estadoEts, String diagnosticoMediaMovel, String diagnosticoEts,
            JsonNode parametrosMediaMovel, JsonNode parametrosEts, boolean comparavel, boolean elegivelMape) {}
    public record Metricas(int candidatos, int paresComparaveis, int paresMape, int zerosExcluidosMape,
            int realizadosEmFalta, int semPrevisaoMediaMovel, int semPrevisaoEts,
            String coberturaPercentagem, String maeMediaMovel, String maeEts, String mapeMediaMovel, String mapeEts) {}
    public record GrupoDto(String produtoId, String produto, String unidade, int horizonte, Metricas metricas) {}
    public record Pagina<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
}
