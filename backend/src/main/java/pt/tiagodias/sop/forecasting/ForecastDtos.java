package pt.tiagodias.sop.forecasting;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import pt.tiagodias.sop.imports.ImportDtos.TipoSerie;

public final class ForecastDtos {
    private ForecastDtos() {}
    public record Parametros(@Min(1) @Max(36) Integer janela,
                             @Pattern(regexp = "RECURSIVO") String multiplosPassos,
                             @Pattern(regexp = "AAN") String variante,
                             @Min(6) @Max(120) Integer historicoMeses,
                             @Pattern(regexp = "SEM_RECURSO|MEDIA_MOVEL") String recurso,
                             @Min(1) @Max(36) Integer janelaRecurso) {}
    public record CreateExecucao(@NotNull @Positive Long loteHistoricoId, @NotNull TipoSerie tipoSerie,
                                 @NotNull Instant corteTemporal,
                                 @NotBlank @Pattern(regexp = "[1-9][0-9]{3}-(0[1-9]|1[0-2])") String ultimoPeriodoTreino,
                                 @NotNull @Min(1) @Max(12) Integer horizonteMeses,
                                 @NotBlank @Pattern(regexp = "MEDIA_MOVEL|ETS") String metodo,
                                 @NotNull @Valid Parametros parametros) {}
    public record ExecucaoDto(String id, String loteHistoricoId, String tipoSerie, Instant corteTemporal,
                              String ultimoPeriodoTreino, int horizonteMeses, String metodo,
                              JsonNode parametros, String versaoSoftware, String versaoMotor,
                              String estado, String criadoPor, Instant criadoEm, Instant concluidoEm,
                              int produtosPrevistos, int produtosInsuficientes, int totalLinhas,
                              int produtosRecurso, int produtosErro) {}
    public record LinhaDto(String produtoId, String produto, String unidade, String periodo, int horizonte, String quantidadeBase) {}
    public record DiagnosticoDto(String produtoId, String produto, String estado, String metodoAplicado, String diagnostico,
                                 JsonNode parametrosAplicados) {}
    public record Pagina<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
}
