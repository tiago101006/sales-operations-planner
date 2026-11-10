package pt.tiagodias.sop.monitoring;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;

public final class MonitoringDtos {
    private MonitoringDtos() {}
    public record Create(@NotNull @Positive Long planoId,@NotNull @Positive Long loteRealizadosId,
                         @Positive Long loteOperacionalId,
                         @NotBlank @Pattern(regexp="[1-9][0-9]{3}-(0[1-9]|1[0-2])") String periodoInicio,
                         @NotBlank @Pattern(regexp="[1-9][0-9]{3}-(0[1-9]|1[0-2])") String periodoFim,
                         @NotNull Instant corteRealizados) {}
    public record Avaliacao(String id,String planoId,String loteRealizadosId,String loteOperacionalId,
                            String periodoInicio,String periodoFim,Instant corteRealizados,String versaoCalculo,
                            String criadoPor,Instant criadoEm,long totalCelulas) {}
    public record Operacional(String id,Instant disponivelEm,String unidadesSolicitadas,String unidadesSatisfeitasNoPrazo,
                               Integer diasObservadosComProcura,Integer diasComProcuraSemStock,String stockFecho,String limiteStock,
                               String unidadesPerdidas,String prazoMedioDias,String nivelServicoPercentagem,String ruturaPercentagem,String excessoStock) {}
    public record Celula(String produtoId,String produto,String unidade,String periodo,int horizonte,
                          String quantidadeBase,String quantidadeAprovada,String observacaoId,String quantidadeRealizada,
                          Instant realizadoDisponivelEm,boolean elegivelTemporal,boolean elegivelPrecisao,String motivoExclusao,
                          Operacional operacional,String motivoOperacional) {}
    public record Metricas(int candidatos,int paresMae,int paresMape,int zerosExcluidosMape,int emFalta,int realizadosAposCorte,
                            int planosTardios,int periodosNaoFechados,String coberturaPercentagem,
                            String maeBase,String maeAprovado,String mapeBase,String mapeAprovado,String ganhoAjuste) {}
    public record Horizonte(int horizonte,Metricas metricas) {}
    public record Precisao(String produtoId,String produto,String unidade,Metricas metricas,List<Horizonte> porHorizonte) {}
    public record Operacao(String produtoId,String produto,String unidade,int candidatos,int elegiveisTemporais,int registosConhecidos,
                            int paresServico,int paresRutura,int paresStock,int registosPerdas,
                            String nivelServicoPercentagem,String ruturaPercentagem,String excessoStockMedio,String unidadesPerdidasTotal) {}
}
