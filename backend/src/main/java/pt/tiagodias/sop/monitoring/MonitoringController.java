package pt.tiagodias.sop.monitoring;

import java.net.URI;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pt.tiagodias.sop.scenarios.ScenarioDtos.Pagina;
import static pt.tiagodias.sop.monitoring.MonitoringDtos.*;

@RestController
@RequestMapping("/api/v1/avaliacoes")
public class MonitoringController {
    private final MonitoringService service;private final MonitoringQueries queries;
    public MonitoringController(MonitoringService service,MonitoringQueries queries) { this.service=service;this.queries=queries; }
    @PostMapping
    public ResponseEntity<Avaliacao> create(@Valid @RequestBody Create request,Authentication authentication) { var value=service.create(request,authentication.getName());return ResponseEntity.created(URI.create("/api/v1/avaliacoes/"+value.id())).body(value); }
    @GetMapping
    public Pagina<Avaliacao> list(@RequestParam(required=false)Long planoId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.list(planoId,page,size); }
    @GetMapping("/{id}")
    public Avaliacao get(@PathVariable long id) { return queries.get(id); }
    @GetMapping("/{id}/celulas")
    public Pagina<Celula> cells(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.cells(id,page,size); }
    @GetMapping("/{id}/precisao")
    public Pagina<Precisao> precision(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.precision(id,page,size); }
    @GetMapping("/{id}/operacao")
    public Pagina<Operacao> operation(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.operation(id,page,size); }
}
