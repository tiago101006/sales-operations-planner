package pt.tiagodias.sop.forecasting;

import java.net.URI;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static pt.tiagodias.sop.forecasting.ForecastDtos.*;

@RestController
@RequestMapping("/api/v1/execucoes-previsao")
public class ForecastController {
    private final ForecastService service;
    private final ForecastQueries queries;
    public ForecastController(ForecastService service, ForecastQueries queries) { this.service = service; this.queries = queries; }
    @PostMapping
    public ResponseEntity<ExecucaoDto> create(@Valid @RequestBody CreateExecucao request, Authentication authentication) {
        var execution = service.create(request, authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/execucoes-previsao/" + execution.id())).body(execution);
    }
    @GetMapping
    public Pagina<ExecucaoDto> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) { return queries.list(page, size); }
    @GetMapping("/{id}")
    public ExecucaoDto get(@PathVariable long id) { return queries.get(id); }
    @GetMapping("/{id}/linhas")
    public Pagina<LinhaDto> lines(@PathVariable long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) { return queries.lines(id, page, size); }
    @GetMapping("/{id}/diagnosticos")
    public Pagina<DiagnosticoDto> diagnostics(@PathVariable long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) { return queries.diagnostics(id, page, size); }
}
