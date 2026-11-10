package pt.tiagodias.sop.security;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import pt.tiagodias.sop.identity.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final UtilizadorRepository users;

    public AuthController(AuthenticationManager manager, SecurityContextRepository contexts,
                          SessionAuthenticationStrategy sessions, UtilizadorRepository users) {
        this.manager = manager;
        this.contexts = contexts;
        this.sessions = sessions;
        this.users = users;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public IdentityService.UtilizadorDto login(@Valid @RequestBody LoginRequest login,
                                              HttpServletRequest request, HttpServletResponse response) {
        Authentication auth;
        try {
            if (login.password().getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new BadCredentialsException("Credenciais inválidas.");
            }
            auth = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(login.email(), login.password()));
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
            throw exception;
        }
        sessions.onAuthentication(auth, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return currentUser(auth);
    }

    @GetMapping("/me")
    public IdentityService.UtilizadorDto me(Authentication authentication) { return currentUser(authentication); }

    private IdentityService.UtilizadorDto currentUser(Authentication auth) {
        return users.findByEmail(auth.getName()).map(IdentityService::dto)
                .orElseThrow(() -> new BadCredentialsException("Credenciais inválidas."));
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 200) String password) {}
    public record CsrfResponse(String headerName, String token) {}
}
