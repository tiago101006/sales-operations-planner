package pt.tiagodias.sop.identity;

import java.net.URI;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/utilizadores")
public class UtilizadorController {
    private final IdentityService service;
    private final UtilizadorRepository repository;

    public UtilizadorController(IdentityService service, UtilizadorRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @GetMapping
    public pt.tiagodias.sop.scenarios.ScenarioDtos.Pagina<IdentityService.UtilizadorDto> list(
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {
        return service.list(page,size);
    }

    @GetMapping("/{id}")
    public IdentityService.UtilizadorDto get(@PathVariable Long id) {
        return repository.findById(id).map(IdentityService::dto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilizador não encontrado."));
    }

    @PostMapping
    public ResponseEntity<IdentityService.UtilizadorDto> create(@Valid @RequestBody CreateUser request) {
        var user = service.create(request.nome(), request.email(), request.password(), request.papeis());
        return ResponseEntity.created(URI.create("/api/v1/utilizadores/" + user.id())).body(user);
    }

    public record CreateUser(
            @NotBlank @Size(max = 120) String nome,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotEmpty Set<@NotNull Papel> papeis) {}
}
