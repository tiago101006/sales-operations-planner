package pt.tiagodias.sop.planning;

import java.sql.Statement;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import pt.tiagodias.sop.scenarios.ScenarioDtos.RevisaoDto;
import pt.tiagodias.sop.scenarios.ScenarioQueries;
import pt.tiagodias.sop.shared.VersionConflictException;
import static pt.tiagodias.sop.planning.PlanDtos.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class PlanService {
    private final JdbcTemplate jdbc;
    private final UtilizadorRepository users;
    private final ScenarioQueries scenarios;

    public PlanService(JdbcTemplate jdbc,UtilizadorRepository users,ScenarioQueries scenarios) {
        this.jdbc=jdbc;this.users=users;this.scenarios=scenarios;
    }
    private record Locked(long id,long execution,long author,String state,long version) {}
    private Locked lock(long id,long expected) {
        var revision=jdbc.query("SELECT * FROM revisao_cenario WHERE id=? FOR UPDATE",(rs,n)->
                new Locked(rs.getLong("id"),rs.getLong("execucao_id"),rs.getLong("criado_por"),rs.getString("estado"),rs.getLong("versao_lock")),id)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Revisão não encontrada."));
        if(revision.version()!=expected) { throw new VersionConflictException(revision.version()); }
        return revision;
    }
    private long author(String email) { return users.findByEmail(email).orElseThrow().getId(); }
    private void requireState(Locked revision,String state) {
        if(!revision.state().equals(state)) { throw conflict("A revisão deve estar em "+state+" para esta operação."); }
    }
    private void independent(Locked revision,long actor) {
        if(revision.author()==actor || scenarios.autorDeAjustes(revision.id(),actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A decisão exige um APROVADOR que não criou esta revisão nem participou nos seus ajustes, incluindo os herdados.");
        }
    }
    private long completeLines(Locked revision) {
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM linha_cenario WHERE revisao_id=?",Long.class,revision.id());
        long base=jdbc.queryForObject("SELECT COUNT(*) FROM linha_previsao WHERE execucao_id=?",Long.class,revision.execution());
        if(count==0 || count!=base) { throw conflict("A revisão deve conter todas as células da previsão base."); }
        return count;
    }
    private long insert(String sql,Object... values) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<values.length;i++) { statement.setObject(i+1,values[i]); }
            return statement;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private long event(long revision,String type,long author,String reason) {
        return insert("INSERT INTO evento_revisao(revisao_id,tipo,autor_id,motivo) VALUES (?,?,?,?)",revision,type,author,reason);
    }
    public RevisaoDto submit(long id,Transicao request,String email) {
        var revision=lock(id,request.versaoEsperada());requireState(revision,"RASCUNHO");completeLines(revision);
        long event=event(id,"SUBMISSAO",author(email),null);
        jdbc.update("UPDATE revisao_cenario SET estado='EM_REVISAO',submetido_em=(SELECT ocorrido_em FROM evento_revisao WHERE id=?),versao_lock=versao_lock+1 WHERE id=?",event,id);
        return scenarios.revisao(id);
    }
    public RevisaoDto returnRevision(long id,Devolucao request,String email) {
        var revision=lock(id,request.versaoEsperada());requireState(revision,"EM_REVISAO");
        long actor=author(email);independent(revision,actor);
        if(request.motivo().codePoints().noneMatch(c->!Character.isWhitespace(c) && !Character.isSpaceChar(c))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"O motivo de devolução exige conteúdo.");
        }
        long event=event(id,"DEVOLUCAO",actor,request.motivo().strip());
        jdbc.update("UPDATE revisao_cenario SET estado='DEVOLVIDA',decidido_em=(SELECT ocorrido_em FROM evento_revisao WHERE id=?),versao_lock=versao_lock+1 WHERE id=?",event,id);
        return scenarios.revisao(id);
    }
    public Aprovacao approve(long id,Transicao request,String email) {
        var revision=lock(id,request.versaoEsperada());requireState(revision,"EM_REVISAO");
        long actor=author(email);independent(revision,actor);long count=completeLines(revision);
        long event=event(id,"APROVACAO",actor,null);
        jdbc.update("UPDATE revisao_cenario SET estado='APROVADA',decidido_em=(SELECT ocorrido_em FROM evento_revisao WHERE id=?),versao_lock=versao_lock+1 WHERE id=?",event,id);
        long plan=insert("INSERT INTO plano_publicado(revisao_id,evento_aprovacao_id,versao_contrato) VALUES (?,?,'v1')",id,event);
        int copied=jdbc.update("""
                INSERT INTO linha_plano(plano_id,revisao_id,produto_id,periodo,codigo_produto,unidade,quantidade_base,quantidade_aprovada)
                SELECT ?,l.revisao_id,l.produto_id,l.periodo,p.codigo,p.unidade,b.quantidade_base,l.quantidade_final
                FROM linha_cenario l JOIN produto p ON p.id=l.produto_id
                JOIN linha_previsao b ON b.execucao_id=l.execucao_id AND b.produto_id=l.produto_id AND b.periodo=l.periodo
                WHERE l.revisao_id=?
                """,plan,id);
        if(copied!=count) { throw conflict("Não foi possível copiar todas as células do plano."); }
        return new Aprovacao(Long.toString(plan),Long.toString(id),revision.version()+1);
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
