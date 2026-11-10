package pt.tiagodias.sop.imports;

import java.net.URI;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static pt.tiagodias.sop.imports.ImportDtos.*;

@RestController
@RequestMapping("/api/v1/lotes")
public class ImportController {
    private final ImportService service;
    private final ImportQueries queries;
    public ImportController(ImportService service, ImportQueries queries) { this.service = service; this.queries = queries; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam MultipartFile ficheiro,
                                    @RequestParam(defaultValue = "HISTORICO") String tipo,
                                    @RequestParam(defaultValue = "upload-manual") String origem,
                                    @RequestParam(defaultValue = "v1") String versaoEsquema,
                                    @RequestParam(required = false) Long substituiLoteId, Authentication authentication) {
        var lot = service.upload(ficheiro, tipo, origem, versaoEsquema, substituiLoteId, authentication.getName());
        var location = URI.create("/api/v1/lotes/" + lot.id());
        if (lot.estado().equals("REJEITADO")) {
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "O histórico contém erros. Nenhuma observação foi publicada.");
            problem.setProperty("code", "LOTE_REJEITADO"); problem.setProperty("loteId", lot.id());
            problem.setProperty("totalErros", lot.totalErros());
            return ResponseEntity.unprocessableEntity().location(location).body(problem);
        }
        return ResponseEntity.created(location).body(lot);
    }

    @GetMapping
    public Pagina<LoteDto> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) { return queries.list(page, size); }
    @GetMapping("/{id}")
    public LoteDto get(@PathVariable long id) { return queries.get(id); }
    @GetMapping("/{id}/erros")
    public Pagina<Diagnostico> errors(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) { return queries.errors(id, page, size); }
    @GetMapping("/{id}/observacoes")
    public Pagina<ObservacaoDto> observations(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) { return queries.observations(id, page, size); }
}
