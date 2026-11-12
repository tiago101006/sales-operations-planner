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
    private final InvitationService invitations;

    public UtilizadorController(IdentityService service, UtilizadorRepository repository, InvitationService invitations) {
        this.service = service;
        this.repository = repository;
        this.invitations = invitations;
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
    public ResponseEntity<InvitationService.ConviteDto> create(@Valid @RequestBody CreateUser request,
            org.springframework.security.core.Authentication actor) {
        var invite = invitations.create(request.nome(), request.email(), request.papeis(), actor.getName());
        return ResponseEntity.created(URI.create("/api/v1/utilizadores/" + invite.utilizador().id())).body(invite);
    }

    @PostMapping("/{id}/convites")
    public InvitationService.ConviteDto reissue(@PathVariable Long id,
            @Valid @RequestBody Reissue request, org.springframework.security.core.Authentication actor) {
        return invitations.reissue(id, request.versaoEsperada(), actor.getName());
    }

    public record Reissue(@NotNull @PositiveOrZero Long versaoEsperada) {}

    @PatchMapping("/{id}/estado")
    public IdentityService.UtilizadorDto changeStatus(@PathVariable Long id,
            @Valid @RequestBody ChangeStatus request, org.springframework.security.core.Authentication actor) {
        return service.changeStatus(id, request.ativo(), request.versaoEsperada(), actor.getName());
    }

    public record ChangeStatus(@NotNull Boolean ativo, @NotNull @PositiveOrZero Long versaoEsperada) {}

    public record CreateUser(
            @NotBlank @Size(max = 120) String nome,
            @NotBlank @Email @Size(max = 254) String email,
            @NotEmpty Set<@NotNull Papel> papeis) {}
}
