package pt.tiagodias.sop.imports;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ImportDtos {
    private ImportDtos() {}
    public enum TipoSerie { VENDAS, CONSUMO }
    public record Diagnostico(long numeroLinha, String campo, String codigo, String mensagem) {}
    public record Linha(long numeroLinha, String produto, YearMonth periodo, TipoSerie tipo,
                        BigDecimal quantidade, String unidade, Instant disponivelEm) {}
    public record ParsedCsv(List<Linha> linhas, List<Diagnostico> erros, int totalLinhas) {}
    public record Pagina<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
    public record LoteDto(String id, String tipo, String estado, String origem, String nomeFicheiro,
                          String sha256, String versaoEsquema, String substituiLoteId, String importadoPor,
                          Instant importadoEm, Instant disponivelEm, Instant validadoEm, int totalLinhas, int totalErros) {}
    public record ObservacaoDto(String id, String produtoId, String produto, String periodo, String tipo,
                                String quantidade, String unidade, Instant disponivelEm) {}

    public static void erro(List<Diagnostico> erros, long linha, String campo, String codigo, String mensagem) {
        if (erros.size() < 1000) { erros.add(new Diagnostico(linha, campo, codigo, mensagem)); }
        else if (erros.size() == 1000) {
            erros.add(new Diagnostico(0, "ficheiro", "DIAGNOSTICOS_LIMITADOS", "Existem mais erros; são apresentados os primeiros 1000."));
        }
    }
}
