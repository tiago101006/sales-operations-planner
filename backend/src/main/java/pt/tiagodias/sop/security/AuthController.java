package pt.tiagodias.sop.security;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
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
    private final LoginThrottle throttle;
    private final AccountProfileService profile;

    public AuthController(AuthenticationManager manager, SecurityContextRepository contexts,
                          SessionAuthenticationStrategy sessions, UtilizadorRepository users, LoginThrottle throttle,
                          AccountProfileService profile) {
        this.manager = manager;
        this.contexts = contexts;
        this.sessions = sessions;
        this.users = users;
        this.throttle = throttle;
        this.profile = profile;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public ContaDto login(@Valid @RequestBody LoginRequest login,
                                              HttpServletRequest request, HttpServletResponse response) {
        String email = IdentityService.normalizeEmail(login.email());
        // Use the stored identity so database collation aliases share the same limit.
        var loginUser = users.findByEmail(email).orElse(null);
        String account = loginUser == null ? email : loginUser.getEmail();
        long retry = throttle.reserveAccount(account);
        if (retry > 0) {
            response.setHeader("Retry-After", Long.toString(retry));
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiadas tentativas de entrada. Aguarda antes de tentar novamente.");
        }
        Authentication auth;
        try {
            if (login.password().getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new BadCredentialsException("Credenciais inválidas.");
            }
            auth = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, login.password()));
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
            throw exception;
        }
        sessions.onAuthentication(auth, request, response);
        throttle.succeeded(account);
        request.getSession().setAttribute(CurrentAccountFilter.AUTHENTICATED_AT, System.currentTimeMillis());
        request.getSession().setAttribute(CurrentAccountFilter.ACCESS_VERSION, loginUser == null ? -1L : loginUser.getVersao());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return currentUser(auth);
    }

    @GetMapping("/me")
    public ContaDto me(Authentication authentication) { return currentUser(authentication); }

    private ContaDto currentUser(Authentication auth) {
        var user = users.findByEmail(auth.getName())
                .orElseThrow(() -> new BadCredentialsException("Credenciais inválidas."));
        return new ContaDto(user.getId().toString(), user.getNome(), user.getEmail(), user.getPapeis(),
                user.isAtivo(), user.getVersao(), user.isPasswordDefinida(), profile.hasPhoto(user.getId()));
    }

    public record ContaDto(String id, String nome, String email, Set<Papel> papeis, boolean ativo,
                           long versao, boolean passwordDefinida, boolean temFoto) {}

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 200) String password) {}
    public record CsrfResponse(String headerName, String token) {}
}
