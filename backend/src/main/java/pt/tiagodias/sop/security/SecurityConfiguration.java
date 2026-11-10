package pt.tiagodias.sop.security;

import java.util.List;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import pt.tiagodias.sop.identity.UtilizadorRepository;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    SessionAuthenticationStrategy loginSessionStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository context, CsrfTokenRepository csrf,
                                          UtilizadorRepository users) throws Exception {
        return http
                .addFilterBefore(new CurrentAccountFilter(users, context), AuthorizationFilter.class)
                .csrf(config -> config.csrfTokenRepository(csrf))
                .securityContext(config -> config.securityContextRepository(context))
                .requestCache(config -> config.requestCache(new NullRequestCache()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/status", "/actuator/health", "/actuator/health/**", "/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/utilizadores", "/api/v1/utilizadores/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/avaliacoes", "/api/v1/avaliacoes/**").hasAnyRole("ADMIN", "PLANEADOR", "COMERCIAL", "APROVADOR", "CONSUMIDOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/avaliacoes").hasAnyRole("ADMIN", "PLANEADOR")
                        .requestMatchers("/api/v1/avaliacoes", "/api/v1/avaliacoes/**").denyAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/revisoes/*/submissao").hasAnyRole("ADMIN", "PLANEADOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/revisoes/*/aprovacao", "/api/v1/revisoes/*/devolucao").hasRole("APROVADOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/planos", "/api/v1/planos/**").hasAnyRole("ADMIN", "PLANEADOR", "COMERCIAL", "APROVADOR", "CONSUMIDOR")
                        .requestMatchers("/api/v1/planos", "/api/v1/planos/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/cenarios", "/api/v1/cenarios/**", "/api/v1/revisoes/**", "/api/v1/comparacao-cenarios")
                            .hasAnyRole("ADMIN", "PLANEADOR", "COMERCIAL", "APROVADOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/cenarios", "/api/v1/cenarios/*/revisoes", "/api/v1/revisoes/*/ajustes").hasAnyRole("ADMIN", "PLANEADOR")
                        .requestMatchers("/api/v1/cenarios", "/api/v1/cenarios/**", "/api/v1/revisoes", "/api/v1/revisoes/**", "/api/v1/comparacao-cenarios").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/backtests", "/api/v1/backtests/**").hasAnyRole("ADMIN", "PLANEADOR", "COMERCIAL", "APROVADOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/backtests").hasAnyRole("ADMIN", "PLANEADOR")
                        .requestMatchers("/api/v1/backtests", "/api/v1/backtests/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/execucoes-previsao", "/api/v1/execucoes-previsao/**").hasAnyRole("ADMIN", "PLANEADOR", "COMERCIAL", "APROVADOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/execucoes-previsao").hasAnyRole("ADMIN", "PLANEADOR")
                        .requestMatchers("/api/v1/execucoes-previsao", "/api/v1/execucoes-previsao/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/lotes", "/api/v1/lotes/**").hasAnyRole("ADMIN", "PLANEADOR", "COMERCIAL", "APROVADOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/lotes").hasAnyRole("ADMIN", "PLANEADOR")
                        .requestMatchers("/api/v1/lotes", "/api/v1/lotes/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/produtos", "/api/v1/produtos/**")
                            .hasAnyRole("ADMIN", "PLANEADOR", "COMERCIAL", "APROVADOR", "CONSUMIDOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/produtos").hasAnyRole("ADMIN", "PLANEADOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/produtos/*").hasAnyRole("ADMIN", "PLANEADOR")
                        .requestMatchers("/api/v1/produtos", "/api/v1/produtos/**").denyAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> SecurityErrors.write(res, 401, "AUTHENTICATION_REQUIRED", "Autenticação necessária."))
                        .accessDeniedHandler((req, res, ex) -> SecurityErrors.write(res, 403, "ACCESS_DENIED", "Acesso recusado ou token CSRF inválido.")))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
                .build();
    }
}
