package pt.tiagodias.sop.scenarios;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

public final class ScenarioDtos {
    private ScenarioDtos() {}
    public enum TipoCenario { BASE,OTIMISTA,CONSERVADOR,PERSONALIZADO }
    public record Pressupostos(@NotBlank @Size(max=2000) String descricao) {}
    public record CreateCenario(@NotNull @Positive Long execucaoId,@NotBlank @Size(max=120) String nome,
            @NotNull TipoCenario tipo,@NotNull @DecimalMin("0") @Digits(integer=6,fraction=6) BigDecimal fator,
            @NotNull @Valid Pressupostos pressupostos) {}
    public record CreateRevisao(@NotNull @Positive Long revisaoOrigemId,@NotNull @PositiveOrZero Long versaoOrigemEsperada,
            @DecimalMin("0") @Digits(integer=6,fraction=6) BigDecimal fator,@Valid Pressupostos pressupostos) {}
    public record CreateAjuste(@NotNull @Positive Long produtoId,
            @NotBlank @Pattern(regexp="[1-9][0-9]{3}-(0[1-9]|1[0-2])") String periodo,
            @NotNull @DecimalMin("0") @Digits(integer=14,fraction=4) BigDecimal quantidade,
            @NotBlank @Size(max=1000) String justificacao,@NotNull @PositiveOrZero Long versaoEsperada) {}
    public record CenarioDto(String id,String execucaoId,String nome,String tipo,String criadoPor,Instant criadoEm,
            int numeroRevisoes,String ultimaRevisaoId) {}
    public record RevisaoDto(String id,String cenarioId,String execucaoId,int numero,String revisaoOrigemId,
            String fator,JsonNode pressupostos,String estado,String criadoPor,Instant criadoEm,long versao,
            int totalLinhas,int totalAjustesLocais) {}
    public record CenarioCriado(CenarioDto cenario,RevisaoDto revisao) {}
    public record LinhaDto(String produtoId,String produto,String unidade,String periodo,String quantidadeBase,
            String quantidadeCalculada,String quantidadeFinal,String desvioAbsoluto,String desvioPercentual) {}
    public record AjusteDto(String id,String eventoId,String revisaoId,String produtoId,String periodo,int sequencia,
            String valorAnterior,String valorNovo,String justificacao,String autorId,String autorNome,Instant ocorridoEm,boolean herdado) {}
    public record AjusteCriado(AjusteDto ajuste,long versaoAtual) {}
    public record EventoDto(String id,String revisaoId,String tipo,String autorId,String autorNome,Instant ocorridoEm,String motivo) {}
    public record ValorComparado(String revisaoId,String quantidadeCalculada,String quantidadeFinal,String desvioAbsoluto,String desvioPercentual) {}
    public record PeriodoComparado(String periodo,String quantidadeBase,List<ValorComparado> cenarios) {}
    public record ProdutoComparado(String produtoId,String produto,String unidade,List<PeriodoComparado> periodos) {}
    public record ComparacaoDto(String execucaoId,List<RevisaoDto> revisoes,Pagina<ProdutoComparado> produtos) {}
    public record Pagina<T>(List<T> items,int page,int size,long totalElements,int totalPages) {}
}
