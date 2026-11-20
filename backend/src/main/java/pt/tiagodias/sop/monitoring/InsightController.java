package pt.tiagodias.sop.monitoring;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static pt.tiagodias.sop.monitoring.InsightDtos.*;

@RestController
@RequestMapping("/api/v1/avaliacoes/{id}/analise-inteligente")
public class InsightController {
    private final InsightService service;
    public InsightController(InsightService service) { this.service=service; }
    @GetMapping
    public ResponseEntity<Relatorio> get(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }
    @PostMapping
    public ResponseEntity<Relatorio> create(@PathVariable long id,Authentication auth) {
        var value=service.start(id,auth.getName());
        return ResponseEntity.status(value.estado().equals("EM_CURSO")?202:200).cacheControl(CacheControl.noStore()).body(value);
    }
}
