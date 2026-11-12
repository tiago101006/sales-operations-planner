package pt.tiagodias.sop.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/ativacao")
public class ActivationController {
    private final InvitationService invitations;
    public ActivationController(InvitationService invitations) { this.invitations = invitations; }

    @PostMapping
    public ResponseEntity<Void> activate(@Valid @RequestBody Activation request) {
        invitations.activate(request.token(), request.password(), request.confirmacao());
        return ResponseEntity.noContent().build();
    }
    public record Activation(@NotBlank @Size(max=100) String token,
            @NotBlank @Size(min=12,max=72) String password,
            @NotBlank @Size(min=12,max=72) String confirmacao) {}
}
