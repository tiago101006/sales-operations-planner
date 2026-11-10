package pt.tiagodias.sop;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import pt.tiagodias.sop.imports.OutcomeCsvParser;
import static org.assertj.core.api.Assertions.*;

class OutcomeCsvParserTests {
    private final OutcomeCsvParser parser=new OutcomeCsvParser();
    private static final String HEADER="produto;periodo;quantidade;unidade;disponivel_em\n";
    private final Instant now=Instant.parse("2026-11-10T00:00:00Z");
    @Test void rejectsEncodingHeadersBrokenCsvAndUnclosedMonth() {
        assertThat(parser.parse(new byte[]{(byte)0xc3,0x28},"REALIZADOS",now).errors()).anyMatch(e->e.codigo().equals("UTF8_INVALIDO"));
        for(String text:new String[]{"produto;quantidade\nA;1\n",HEADER+"\"sem fecho",HEADER+"A;2026-11;1;un;2026-11-10T00:00:00Z\n",HEADER+"A;2025-01;1.00001;un;2025-02-01T00:00:00Z\n",HEADER}) {
            assertThat(parser.parse(text.getBytes(StandardCharsets.UTF_8),"REALIZADOS",now).errors()).isNotEmpty();
        }
    }
    @Test void preservesQuotedValuesAndRejectsOversizedFilesByRowCount() {
        var result=parser.parse((HEADER+"\"A\";2025-01;0;\"un\";2025-02-01T00:00:00Z\n").getBytes(StandardCharsets.UTF_8),"REALIZADOS",now);
        assertThat(result.errors()).isEmpty();assertThat(result.rows().get(0).quantity()).isZero();
        var text=new StringBuilder(HEADER);
        for(int i=0;i<100001;i++) { text.append("A").append(i).append(";2025-01;1;un;2025-02-01T00:00:00Z\n"); }
        assertThat(parser.parse(text.toString().getBytes(StandardCharsets.UTF_8),"REALIZADOS",now).errors()).anyMatch(e->e.codigo().equals("LIMITE_LINHAS"));
    }
}
