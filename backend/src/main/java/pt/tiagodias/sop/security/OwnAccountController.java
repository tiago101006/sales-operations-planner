package pt.tiagodias.sop.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import pt.tiagodias.sop.identity.AccountProfileService;

@RestController
@RequestMapping("/api/v1/auth")
public class OwnAccountController {
    private final AccountProfileService profile;
    private final LoginThrottle throttle;

    public OwnAccountController(AccountProfileService profile, LoginThrottle throttle) {
        this.profile = profile;
        this.throttle = throttle;
    }

    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePassword command, Authentication auth,
                                                HttpServletRequest request, HttpServletResponse response) {
        long retry = throttle.reservePasswordChange(auth.getName());
        if (retry > 0) {
            response.setHeader("Retry-After", Long.toString(retry));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiadas tentativas. Aguarda antes de tentar alterar a palavra-passe.");
        }
        profile.changePassword(auth.getName(), version(request), command.passwordAtual(), command.novaPassword(),
                command.confirmacao());
        throttle.passwordChanged(auth.getName());
        new SecurityContextLogoutHandler().logout(request, response, auth);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/foto", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> photo(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .body(profile.photo(auth.getName(), version(request)));
    }

    @PutMapping(value = "/foto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> savePhoto(@RequestPart("foto") MultipartFile file, Authentication auth,
                                           HttpServletRequest request) {
        long sessionVersion = version(request);
        profile.savePhoto(auth.getName(), sessionVersion, ProfilePhoto.normalize(file));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/foto")
    public ResponseEntity<Void> removePhoto(Authentication auth, HttpServletRequest request) {
        profile.removePhoto(auth.getName(), version(request));
        return ResponseEntity.noContent().build();
    }

    private static long version(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(CurrentAccountFilter.ACCESS_VERSION) instanceof Long version) {
            return version;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A sessão terminou. Volta a entrar.");
    }

    public record ChangePassword(
            @NotBlank @Size(max = 200) String passwordAtual,
            @NotBlank @Size(max = 200) String novaPassword,
            @NotBlank @Size(max = 200) String confirmacao) {}
}
