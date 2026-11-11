package pt.tiagodias.sop.shared;

import java.sql.Date;
import java.time.YearMonth;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.imports.ImportQueries;

/** Bound predicates on an already scoped read query, before counting/pagination. */
public final class SearchPage {
    private SearchPage() {}
    public record Result<T>(List<T> items,long total,int pages) {}
    public record Filter(String sql,Object[] args) {}
    public static Filter filter(String sql,String columns,String month,Object... args) {
        var values=new ArrayList<Object>(Arrays.asList(args));
        StringBuilder where=new StringBuilder(" WHERE 1=1");
        var attrs=RequestContextHolder.getRequestAttributes();
        if(attrs instanceof ServletRequestAttributes web) {
            var request=web.getRequest();
            String q=Optional.ofNullable(request.getParameter("busca")).orElse("").strip();
            if(q.length()>160) throw invalid("A pesquisa aceita até 160 caracteres.");
            if(q.matches("#[0-9]+") && Arrays.stream(columns.split(",")).anyMatch(c -> c.strip().equals("id"))) {
                where.append(" AND CAST(id AS CHAR)=?");values.add(q.substring(1));
            } else if(!q.isEmpty()) {
                // Column names are constants supplied by repositories, never request input.
                where.append(" AND CONVERT(CONCAT_WS(' ',").append(columns).append(") USING utf8mb4) COLLATE utf8mb4_0900_ai_ci LIKE CONVERT(? USING utf8mb4) COLLATE utf8mb4_0900_ai_ci ESCAPE '!'");
                values.add("%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
            }
            String from=request.getParameter("desde"),to=request.getParameter("ate");
            YearMonth start=parse(from),end=parse(to);
            if(end!=null && end.getYear()==9999 && end.getMonthValue()==12) throw invalid("O mês final deve ser anterior a 9999-12.");
            if(start!=null && end!=null && start.isAfter(end)) throw invalid("O mês inicial não pode ser posterior ao final.");
            if(month!=null) {
                if(start!=null) { where.append(" AND ").append(month).append(">=?");values.add(Date.valueOf(start.atDay(1))); }
                if(end!=null) { where.append(" AND ").append(month).append("<?");values.add(Date.valueOf(end.plusMonths(1).atDay(1))); }
            }
        }
        return new Filter("SELECT * FROM ("+sql+") busca"+where,values.toArray());
    }
    public static <T> Result<T> page(JdbcTemplate jdbc,String sql,String order,RowMapper<T> mapper,int page,int size,String columns,String month,Object... args) {
        int offset=ImportQueries.offset(page,size);
        var query=filter(sql,columns,month,args);
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM ("+query.sql()+") total_busca",Long.class,query.args());
        var values=new ArrayList<Object>(Arrays.asList(query.args()));values.add(size);values.add(offset);
        return new Result<>(jdbc.query(query.sql()+" ORDER BY "+order+" LIMIT ? OFFSET ?",mapper,values.toArray()),count,(int)((count+size-1)/size));
    }
    public static boolean monthMatches(String month) {
        var attrs=RequestContextHolder.getRequestAttributes();
        if(!(attrs instanceof ServletRequestAttributes web)) return true;
        var start=parse(web.getRequest().getParameter("desde"));var end=parse(web.getRequest().getParameter("ate"));var value=YearMonth.parse(month);
        return (start==null || !value.isBefore(start)) && (end==null || !value.isAfter(end));
    }
    private static YearMonth parse(String value) {
        if(value==null || value.isBlank()) return null;
        try { if(!value.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException();return YearMonth.parse(value); }
        catch(RuntimeException error) { throw invalid("Indica os meses no formato AAAA-MM."); }
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,message); }
}
