package pt.tiagodias.sop.imports;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;
import static pt.tiagodias.sop.imports.ImportDtos.*;

@Component
public class OutcomeCsvParser {
    public static final List<String> FIELDS=List.of("unidades_solicitadas","unidades_satisfeitas_no_prazo","dias_observados_com_procura","dias_com_procura_sem_stock","stock_fecho","limite_stock","unidades_perdidas","prazo_medio_dias");
    public record Row(long line,String product,YearMonth month,String unit,Instant available,BigDecimal quantity,Map<String,BigDecimal> values) {}
    public record Parsed(List<Row> rows,List<Diagnostico> errors,int count) {}
    private record Key(String product,YearMonth month) {}
    public Parsed parse(byte[] bytes,String type,Instant now) {
        var rows=new ArrayList<Row>();var errors=new ArrayList<Diagnostico>();int count=0;
        String text;
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException exception) { erro(errors,0,"ficheiro","UTF8_INVALIDO","O ficheiro deve usar UTF-8.");return new Parsed(rows,errors,0); }
        if(text.startsWith("\uFEFF")) { text=text.substring(1); }
        boolean operational=type.equals("OPERACIONAL");
        var headers=new HashSet<>(List.of("produto","periodo","unidade","disponivel_em"));
        if(operational) { headers.addAll(FIELDS); } else { headers.add("quantidade"); }
        var format=CSVFormat.RFC4180.builder().setDelimiter(';').setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(false)
                .setAllowMissingColumnNames(false).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
        var keys=new HashSet<Key>();
        try(var parser=format.parse(new StringReader(text))) {
            if(parser.getHeaderNames().size()!=headers.size() || !new HashSet<>(parser.getHeaderNames()).equals(headers)) {
                erro(errors,1,"cabecalho","CABECALHO_INVALIDO","Cabeçalho incompatível com o esquema v1 de "+type+".");return new Parsed(rows,errors,0);
            }
            for(var record:parser) {
                count++;long line=record.getRecordNumber()+1;
                if(count>HistoricoCsvParser.MAX_ROWS) { if(count==HistoricoCsvParser.MAX_ROWS+1) { erro(errors,line,"ficheiro","LIMITE_LINHAS","Máximo de 100000 registos."); }continue; }
                try {
                    if(!record.isConsistent()) { throw new IllegalArgumentException("Número de colunas inválido."); }
                    String product=record.get("produto").strip(),unit=record.get("unidade").strip(),period=record.get("periodo").strip();
                    if(product.length()>64 || !product.matches("[A-Za-z0-9._-]+")) { throw new IllegalArgumentException("Código de produto inválido."); }
                    if(unit.isBlank() || unit.length()>20) { throw new IllegalArgumentException("Unidade inválida."); }
                    if(!period.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])")) { throw new IllegalArgumentException("Período inválido; usa AAAA-MM."); }
                    var month=YearMonth.parse(period);
                    String value=record.get("disponivel_em").strip();
                    if(value.length()>40 || !value.endsWith("Z")) { throw new IllegalArgumentException("Disponibilidade deve usar UTC com Z."); }
                    Instant available=Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
                    // Monthly outcomes are complete only at the start of the following month.
                    if(available.isAfter(now) || available.isBefore(month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
                        throw new IllegalArgumentException("Disponibilidade deve ser posterior ao mês fechado e não pode estar no futuro.");
                    }
                    if(!keys.add(new Key(product,month))) { erro(errors,line,"produto,periodo","CHAVE_DUPLICADA","Produto e período repetidos no ficheiro.");continue; }
                    BigDecimal quantity=null;var values=new LinkedHashMap<String,BigDecimal>();
                    if(operational) {
                        boolean any=false;
                        for(String field:FIELDS) { var number=number(record.get(field),true,field.equals("prazo_medio_dias")?6:14);values.put(field,number);any|=number!=null; }
                        if(!any) { throw new IllegalArgumentException("Indica pelo menos um campo operacional."); }
                        paired(values,"unidades_solicitadas","unidades_satisfeitas_no_prazo");
                        paired(values,"dias_observados_com_procura","dias_com_procura_sem_stock");
                        for(String field:List.of("dias_observados_com_procura","dias_com_procura_sem_stock")) {
                            var n=values.get(field);
                            if(n!=null && (n.stripTrailingZeros().scale()>0 || n.compareTo(BigDecimal.valueOf(month.lengthOfMonth()))>0)) { throw new IllegalArgumentException("Contadores de dias devem ser inteiros e caber no mês."); }
                        }
                    } else { quantity=number(record.get("quantidade"),false,14); }
                    rows.add(new Row(line,product,month,unit,available,quantity,Collections.unmodifiableMap(values)));
                } catch(IllegalArgumentException | DateTimeException exception) { erro(errors,line,"linha","VALOR_INVALIDO",exception.getMessage()==null?"Valor inválido.":exception.getMessage()); }
            }
            if(count==0) { erro(errors,2,"ficheiro","SEM_OBSERVACOES","O ficheiro não contém registos."); }
        } catch(IOException | UncheckedIOException | IllegalArgumentException exception) { erro(errors,count+1L,"ficheiro","CSV_INVALIDO","Confirma cabeçalho, aspas e separador ;."); }
        return new Parsed(rows,errors,count);
    }
    private static BigDecimal number(String text,boolean optional,int digits) {
        String value=text.strip();if(optional && value.isEmpty()) { return null; }
        if(value.length()>24 || !value.matches("[0-9]+(\\.[0-9]{1,4})?")) { throw new IllegalArgumentException("Quantidade inválida: não negativa, ponto decimal e até quatro casas."); }
        var number=new BigDecimal(value).setScale(4);
        if(number.compareTo(BigDecimal.TEN.pow(digits).subtract(new BigDecimal("0.0001")))>0) { throw new IllegalArgumentException("Quantidade acima do intervalo suportado."); }
        return number;
    }
    private static void paired(Map<String,BigDecimal> fields,String denominator,String numerator) {
        var d=fields.get(denominator);var n=fields.get(numerator);
        if((d==null)!=(n==null) || (d!=null && n.compareTo(d)>0)) { throw new IllegalArgumentException("Preenche o par "+denominator+"/"+numerator+"; o segundo não pode exceder o primeiro."); }
    }
}
