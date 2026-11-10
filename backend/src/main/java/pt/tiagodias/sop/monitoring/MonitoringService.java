package pt.tiagodias.sop.monitoring;

import java.sql.Statement;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import pt.tiagodias.sop.imports.ImportQueries;
import pt.tiagodias.sop.planning.PlanQueries;
import static pt.tiagodias.sop.monitoring.MonitoringDtos.*;

@Service
public class MonitoringService {
    private final JdbcTemplate jdbc;private final MonitoringQueries queries;private final ImportQueries imports;private final PlanQueries plans;private final UtilizadorRepository users;
    public MonitoringService(JdbcTemplate jdbc,MonitoringQueries queries,ImportQueries imports,PlanQueries plans,UtilizadorRepository users) { this.jdbc=jdbc;this.queries=queries;this.imports=imports;this.plans=plans;this.users=users; }
    @Transactional
    public Avaliacao create(Create request,String email) {
        Instant cut=request.corteRealizados().truncatedTo(ChronoUnit.MICROS);
        YearMonth first=YearMonth.parse(request.periodoInicio()),last=YearMonth.parse(request.periodoFim());
        if(last.isBefore(first) || ChronoUnit.MONTHS.between(first,last)>119 || cut.isAfter(Instant.now())) { throw invalid("Janela entre 1 e 120 meses e corte sem futuro."); }
        var plan=plans.get(request.planoId());if(plan.publicadoEm().isAfter(cut)) { throw conflict("O plano ainda não estava publicado no corte."); }
        lot(request.loteRealizadosId(),"REALIZADOS",cut);if(request.loteOperacionalId()!=null) { lot(request.loteOperacionalId(),"OPERACIONAL",cut); }
        var rows=jdbc.query("""
                SELECT p.produto_id,p.periodo,o.id observacao_id,o.disponivel_em
                FROM linha_plano p LEFT JOIN observacao o ON o.lote_id=? AND o.produto_id=p.produto_id AND o.periodo=p.periodo AND o.tipo='PROCURA_REALIZADA'
                WHERE p.plano_id=? AND p.periodo BETWEEN ? AND ? ORDER BY p.produto_id,p.periodo
                """,(rs,n)->{
            var month=YearMonth.from(rs.getDate("periodo").toLocalDate());
            boolean timely=plan.publicadoEm().isBefore(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
            boolean closed=!month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(cut);
            var available=ImportQueries.instant(rs,"disponivel_em");
            String reason=!timely?"PLANO_PUBLICADO_TARDIAMENTE":!closed?"PERIODO_NAO_FECHADO":available==null?"SEM_REALIZADO":available.isAfter(cut)?"REALIZADO_APOS_CORTE":null;
            return new Cell(rs.getLong("produto_id"),month.atDay(1),rs.getObject("observacao_id",Long.class),timely && closed,reason);
        },request.loteRealizadosId(),request.planoId(),first.atDay(1),last.atDay(1));
        if(rows.isEmpty()) { throw invalid("A janela não contém células deste plano."); }
        long author=users.findByEmail(email).orElseThrow().getId();var key=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var ps=connection.prepareStatement("INSERT INTO avaliacao(plano_id,lote_realizados_id,lote_operacional_id,periodo_inicio,periodo_fim,corte_realizados,versao_calculo,criado_por) VALUES (?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1,request.planoId());ps.setLong(2,request.loteRealizadosId());ps.setObject(3,request.loteOperacionalId());ps.setObject(4,first.atDay(1));ps.setObject(5,last.atDay(1));ps.setObject(6,LocalDateTime.ofInstant(cut,ZoneOffset.UTC));ps.setString(7,MonitoringMetrics.VERSION);ps.setLong(8,author);return ps;
        },key);
        long id=Objects.requireNonNull(key.getKey()).longValue();
        jdbc.batchUpdate("INSERT INTO avaliacao_celula(avaliacao_id,plano_id,lote_realizados_id,produto_id,periodo,observacao_id,elegivel_temporal,motivo_exclusao) VALUES (?,?,?,?,?,?,?,?)",rows,500,(ps,row)->{
            ps.setLong(1,id);ps.setLong(2,request.planoId());ps.setLong(3,request.loteRealizadosId());ps.setLong(4,row.product());ps.setObject(5,row.period());ps.setObject(6,row.observation());ps.setBoolean(7,row.temporal());ps.setString(8,row.reason());
        });
        return queries.get(id);
    }
    private record Cell(long product,LocalDate period,Long observation,boolean temporal,String reason) {}
    private void lot(long id,String type,Instant cut) {
        var lot=imports.get(id);
        if(!lot.tipo().equals(type) || !lot.estado().equals("VALIDADO") || lot.disponivelEm().isAfter(cut) || lot.validadoEm()==null || lot.validadoEm().isAfter(cut)) { throw conflict("O lote deve ser "+type+", validado e disponível no corte."); }
    }
    private static ResponseStatusException invalid(String text) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,text); }
    private static ResponseStatusException conflict(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
}
