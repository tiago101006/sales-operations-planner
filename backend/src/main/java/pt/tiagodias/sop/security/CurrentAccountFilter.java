package pt.tiagodias.sop.security;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reavalia estado e papeis para nao conservar permissoes revogadas numa sessao. */
final class CurrentAccountFilter extends OncePerRequestFilter {
    static final String AUTHENTICATED_AT = CurrentAccountFilter.class.getName() + ".authenticatedAt";
    static final String ACCESS_VERSION = CurrentAccountFilter.class.getName() + ".accessVersion";
    private static final long MAX_SESSION_MILLIS = java.time.Duration.ofHours(8).toMillis();
    private final UtilizadorRepository users;
    private final SecurityContextRepository contexts;

    CurrentAccountFilter(UtilizadorRepository users, SecurityContextRepository contexts) {
        this.users = users;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            var session = request.getSession(false);
            Object created = session == null ? null : session.getAttribute(AUTHENTICATED_AT);
            if (!(created instanceof Long started) || System.currentTimeMillis() - started >= MAX_SESSION_MILLIS) {
                SecurityContextHolder.clearContext();
                if (session != null) { session.invalidate(); }
                SecurityErrors.write(response, 401, "SESSION_EXPIRED", "A sessão terminou. Volta a entrar.");
                return;
            }
            var user = users.findByEmail(authentication.getName()).orElse(null);
            if (user == null || !user.isAtivo() || !user.isPasswordDefinida() || !(session.getAttribute(ACCESS_VERSION) instanceof Long version)
                    || version.longValue() != user.getVersao()) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
                SecurityErrors.write(response, 401, "SESSION_REVOKED", "Sessão inválida.");
                return;
            }
            var authorities = user.getPapeis().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.getEmail(), null, authorities));
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }
}
