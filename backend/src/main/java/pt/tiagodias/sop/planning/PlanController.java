package pt.tiagodias.sop.planning;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pt.tiagodias.sop.scenarios.ScenarioDtos.*;
import static pt.tiagodias.sop.planning.PlanDtos.*;

@RestController
public class PlanController {
    private final PlanService service;
    private final PlanQueries queries;
    public PlanController(PlanService service,PlanQueries queries) { this.service=service;this.queries=queries; }

    @PostMapping("/api/v1/revisoes/{id}/submissao")
    public RevisaoDto submit(@PathVariable long id,@Valid @RequestBody Transicao request,Authentication authentication) {
        return service.submit(id,request,authentication.getName());
    }
    @PostMapping("/api/v1/revisoes/{id}/devolucao")
    public RevisaoDto returnRevision(@PathVariable long id,@Valid @RequestBody Devolucao request,Authentication authentication) {
        return service.returnRevision(id,request,authentication.getName());
    }
    @PostMapping("/api/v1/revisoes/{id}/aprovacao")
    public ResponseEntity<Aprovacao> approve(@PathVariable long id,@Valid @RequestBody Transicao request,Authentication authentication) {
        var approval=service.approve(id,request,authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/planos/"+approval.planoId())).body(approval);
    }
    @GetMapping("/api/v1/planos")
    public Pagina<PlanoDto> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) {
        return queries.list(page,size);
    }
    @GetMapping("/api/v1/planos/{id}")
    public PlanoDto get(@PathVariable long id) { return queries.get(id); }
    @GetMapping("/api/v1/planos/{id}/linhas")
    public Pagina<LinhaPlanoDto> lines(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) {
        return queries.lines(id,page,size);
    }
    @GetMapping("/api/v1/planos/{id}/exportacao.csv")
    public void export(@PathVariable long id,HttpServletResponse response)throws IOException {
        queries.get(id); // Validate before starting the download, so missing plans return JSON 404.
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"plano-"+id+".csv\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        queries.export(id,new OutputStreamWriter(response.getOutputStream(),StandardCharsets.UTF_8));
    }
}
