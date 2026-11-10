package pt.tiagodias.sop.planning;

import java.time.Instant;
import jakarta.validation.constraints.*;

public final class PlanDtos {
    private PlanDtos() {}

    public record Transicao(@NotNull @PositiveOrZero Long versaoEsperada) {}
    public record Devolucao(@NotNull @PositiveOrZero Long versaoEsperada,
                            @NotBlank @Size(max=1000) String motivo) {}
    public record Aprovacao(String planoId,String revisaoId,long versaoAtual) {}
    public record PlanoDto(String id,String revisaoId,String cenarioId,String execucaoId,
                           String versaoContrato,String aprovadoPor,Instant aprovadoEm,
                           Instant publicadoEm,long totalLinhas) {}
    public record LinhaPlanoDto(String produtoId,String produto,String periodo,String unidade,
                                String quantidadeBase,String quantidade) {}
}
