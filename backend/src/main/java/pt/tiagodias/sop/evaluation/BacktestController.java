package pt.tiagodias.sop.evaluation;

import java.net.URI;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static pt.tiagodias.sop.evaluation.BacktestDtos.*;

@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestController {
    private final BacktestService service;private final BacktestQueries queries;
    public BacktestController(BacktestService service,BacktestQueries queries) { this.service=service;this.queries=queries; }
    @PostMapping
    public ResponseEntity<BacktestDto> create(@Valid @RequestBody CreateBacktest request,Authentication authentication) {
        var result=service.create(request,authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/backtests/"+result.id())).body(result);
    }
    @GetMapping
    public Pagina<BacktestDto> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.list(page,size); }
    @GetMapping("/sugestao")
    public BacktestQueries.Suggestion suggestion(@RequestParam long loteHistoricoId,@RequestParam(defaultValue="VENDAS")String tipoSerie,
            @RequestParam(defaultValue="3")int horizonteMeses) { return queries.suggestion(loteHistoricoId,tipoSerie,horizonteMeses); }
    @GetMapping("/{id}")
    public BacktestDto get(@PathVariable long id) { return queries.get(id); }
    @GetMapping("/{id}/celulas")
    public Pagina<CelulaDto> cells(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.cells(id,page,size); }
    @GetMapping("/{id}/comparacao")
    public Pagina<GrupoDto> comparison(@PathVariable long id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size) { return queries.comparison(id,page,size); }
}
