package pt.tiagodias.sop.imports;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;
import static pt.tiagodias.sop.imports.ImportDtos.*;

@Component
public class HistoricoCsvParser {
    public static final int MAX_ROWS = 100_000;
    private static final Set<String> HEADER = Set.of("produto", "periodo", "tipo", "quantidade", "unidade", "disponivel_em");
    private record Chave(String produto, YearMonth periodo, TipoSerie tipo) {}

    public ParsedCsv parse(byte[] bytes, Instant agora) {
        var rows = new ArrayList<Linha>();
        var errors = new ArrayList<Diagnostico>();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            erro(errors, 0, "ficheiro", "UTF8_INVALIDO", "O ficheiro deve usar UTF-8.");
            return new ParsedCsv(rows, errors, 0);
        }
        if (text.startsWith("\uFEFF")) { text = text.substring(1); }
        int count = 0;
        var keys = new HashSet<Chave>();
        var format = CSVFormat.RFC4180.builder().setDelimiter(';').setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(false).setAllowMissingColumnNames(false).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
        try (var parser = format.parse(new StringReader(text))) {
            if (parser.getHeaderNames().size() != HEADER.size() || !new HashSet<>(parser.getHeaderNames()).equals(HEADER)) {
                erro(errors, 1, "cabecalho", "CABECALHO_INVALIDO", "Campos esperados: produto;periodo;tipo;quantidade;unidade;disponivel_em.");
                return new ParsedCsv(rows, errors, 0);
            }
            for (var record : parser) {
                count++;
                long line = record.getRecordNumber() + 1;
                if (count > MAX_ROWS) {
                    if (count == MAX_ROWS + 1) { erro(errors, line, "ficheiro", "LIMITE_LINHAS", "Máximo de 100000 registos por ficheiro."); }
                    continue;
                }
                if (!record.isConsistent()) {
                    erro(errors, line, "linha", "COLUNAS_INVALIDAS", "O número de colunas difere do cabeçalho.");
                    continue;
                }
                String product = record.get("produto").strip();
                String unit = record.get("unidade").strip();
                boolean valid = true;
                if (product.length() > 64 || !product.matches("[A-Za-z0-9._-]+")) {
                    erro(errors, line, "produto", "PRODUTO_INVALIDO", "Indica um código de produto válido até 64 caracteres."); valid = false;
                }
                if (unit.isBlank() || unit.length() > 20) {
                    erro(errors, line, "unidade", "UNIDADE_INVALIDA", "Indica a unidade de planeamento, até 20 caracteres."); valid = false;
                }
                YearMonth month = null;
                try {
                    String value = record.get("periodo").strip();
                    if (!value.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])")) { throw new IllegalArgumentException(); }
                    month = YearMonth.parse(value);
                } catch (IllegalArgumentException | DateTimeException exception) {
                    erro(errors, line, "periodo", "PERIODO_INVALIDO", "Usa AAAA-MM, com ano entre 1000 e 9999."); valid = false;
                }
                TipoSerie type = null;
                try { type = TipoSerie.valueOf(record.get("tipo").strip()); }
                catch (IllegalArgumentException exception) {
                    erro(errors, line, "tipo", "TIPO_INVALIDO", "Usa VENDAS ou CONSUMO."); valid = false;
                }
                BigDecimal quantity = null;
                try {
                    String value = record.get("quantidade").strip();
                    if (value.length() > 24 || !value.matches("[0-9]+(\\.[0-9]{1,4})?")) { throw new IllegalArgumentException(); }
                    quantity = new BigDecimal(value).setScale(4);
                    if (quantity.compareTo(new BigDecimal("99999999999999.9999")) > 0) { throw new IllegalArgumentException(); }
                } catch (IllegalArgumentException exception) {
                    erro(errors, line, "quantidade", "QUANTIDADE_INVALIDA", "Quantidade não negativa, ponto decimal, até 14 algarismos inteiros e 4 decimais."); valid = false;
                }
                Instant available = null;
                try {
                    String value = record.get("disponivel_em").strip();
                    if (value.length() > 40 || !value.endsWith("Z")) { throw new IllegalArgumentException(); }
                    available = Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
                    if (available.isAfter(agora) || (month != null && available.atZone(ZoneOffset.UTC).toLocalDate().isBefore(month.atEndOfMonth()))) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException | DateTimeException exception) {
                    erro(errors, line, "disponivel_em", "DISPONIBILIDADE_INVALIDA", "Usa data ISO-8601 UTC com Z, sem futuro e a partir do último dia do mês observado."); valid = false;
                }
                if (month != null && type != null && !keys.add(new Chave(product, month, type))) {
                    erro(errors, line, "produto,periodo,tipo", "CHAVE_DUPLICADA", "Produto, período e tipo repetidos neste ficheiro."); valid = false;
                }
                if (valid) { rows.add(new Linha(line, product, month, type, quantity, unit, available)); }
            }
            if (count == 0) { erro(errors, 2, "ficheiro", "SEM_OBSERVACOES", "O ficheiro não contém observações."); }
        } catch (IOException | UncheckedIOException | IllegalArgumentException exception) {
            erro(errors, count + 1L, "ficheiro", "CSV_INVALIDO", "CSV inválido: confirma cabeçalho, aspas e separador ponto e vírgula.");
        }
        return new ParsedCsv(rows, errors, count);
    }
}
