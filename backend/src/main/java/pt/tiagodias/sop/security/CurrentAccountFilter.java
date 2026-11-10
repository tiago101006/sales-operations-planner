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
            var user = users.findByEmail(authentication.getName()).orElse(null);
            if (user == null || !user.isAtivo()) {
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
