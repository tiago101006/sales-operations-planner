package pt.tiagodias.sop.security;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

final class LoginRequestFilter extends OncePerRequestFilter {
    private final LoginThrottle throttle;
    LoginRequestFilter(LoginThrottle throttle) { this.throttle = throttle; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getMethod().equals("POST") && java.util.Set.of("/api/v1/auth/login", "/api/v1/auth/ativacao").contains(request.getServletPath())) {
            // Forwarded headers are untrusted input. The TCP peer is the address used here.
            long retry = throttle.reserveAddress(request.getRemoteAddr());
            if (retry > 0) {
                response.setHeader("Retry-After", Long.toString(retry));
                SecurityErrors.write(response, 429, "LOGIN_THROTTLED", "Demasiadas tentativas de entrada. Aguarda antes de tentar novamente.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
