package pt.tiagodias.sop.scenarios;

import java.net.URI;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static pt.tiagodias.sop.scenarios.ScenarioDtos.*;

@RestController
public class ScenarioController {
    private final ScenarioService service;private final ScenarioQueries queries;
    public ScenarioController(ScenarioService service,ScenarioQueries queries) { this.service=service;this.queries=queries; }
    @PostMapping("/api/v1/cenarios")
    public ResponseEntity<CenarioCriado> create(@Valid @RequestBody CreateCenario request,Authentication authentication) {
        var created=service.create(request,authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/cenarios/"+created.cenario().id())).body(created);
    }
    @GetMapping("/api/v1/cenarios")
    public Pagina<CenarioDto> list(@RequestParam(required=false)Long execucaoId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.cenarios(execucaoId,page,size); }
    @GetMapping("/api/v1/cenarios/{id}")
    public CenarioDto scenario(@PathVariable long id) { return queries.cenario(id); }
    @GetMapping("/api/v1/cenarios/{id}/revisoes")
    public Pagina<RevisaoDto> revisions(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.revisoes(id,page,size); }
    @PostMapping("/api/v1/cenarios/{id}/revisoes")
    public ResponseEntity<RevisaoDto> revise(@PathVariable long id,@Valid @RequestBody CreateRevisao request,Authentication authentication) {
        var revision=service.revise(id,request,authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/revisoes/"+revision.id())).body(revision);
    }
    @GetMapping("/api/v1/revisoes/{id}")
    public RevisaoDto revision(@PathVariable long id) { return queries.revisao(id); }
    @GetMapping("/api/v1/revisoes")
    public Pagina<ResumoRevisaoDto> queue(@RequestParam(defaultValue="EM_REVISAO")String estado,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) {
        return queries.fila(estado,page,size);
    }
    @GetMapping("/api/v1/revisoes/{id}/linhas")
    public Pagina<LinhaDto> lines(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.linhas(id,page,size); }
    @PostMapping("/api/v1/revisoes/{id}/ajustes")
    public ResponseEntity<AjusteCriado> adjust(@PathVariable long id,@Valid @RequestBody CreateAjuste request,Authentication authentication) {
        var adjusted=service.adjust(id,request,authentication.getName());
        return ResponseEntity.status(201).body(adjusted);
    }
    @GetMapping("/api/v1/revisoes/{id}/ajustes")
    public Pagina<AjusteDto> adjustments(@PathVariable long id,@RequestParam(defaultValue="false")boolean incluirHerdados,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.ajustes(id,incluirHerdados,page,size); }
    @GetMapping("/api/v1/revisoes/{id}/eventos")
    public Pagina<EventoDto> events(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.eventos(id,page,size); }
    @GetMapping("/api/v1/comparacao-cenarios")
    public ComparacaoDto compare(@RequestParam List<Long> revisaoIds,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size,
            @RequestParam(defaultValue="false") boolean porPeriodo) {
        return queries.comparar(revisaoIds, page, size, porPeriodo);
    }
}
