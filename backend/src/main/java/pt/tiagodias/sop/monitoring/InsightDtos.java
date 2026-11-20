package pt.tiagodias.sop.monitoring;

import java.time.Instant;
import java.util.List;
import static pt.tiagodias.sop.monitoring.MonitoringDtos.*;

/** The application explains calculated evidence; AI only prioritizes verified recommendations. */
public final class InsightDtos {
    private InsightDtos() {}
    public record Secao(String titulo, List<String> paragrafos) {}
    public record Analise(String titulo, List<Secao> secoes) {}
    public record Produto(Precisao precisao, Operacao operacao, List<Celula> celulas) {}
    public record Contexto(String avaliacaoId, String planoId, String loteRealizadosId, String loteOperacionalId,
                           String periodoInicio, String periodoFim, Instant corteRealizados, String versaoCalculo) {}
    public record Snapshot(Contexto contexto, List<Produto> produtos) {}
    public record Relatorio(String estado, String mensagem, Instant geradoEm, String modelo,
                            String versaoPrompt, String hashDados, Analise analise, List<Produto> produtos) {}
}
