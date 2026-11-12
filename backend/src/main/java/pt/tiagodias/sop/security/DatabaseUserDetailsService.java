package pt.tiagodias.sop.security;

import pt.tiagodias.sop.identity.IdentityService;
import pt.tiagodias.sop.identity.UtilizadorRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UtilizadorRepository repository;

    public DatabaseUserDetailsService(UtilizadorRepository repository) { this.repository = repository; }

    @Override
    public UserDetails loadUserByUsername(String email) {
        var user = repository.findByEmail(IdentityService.normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas."));
        return User.withUsername(user.getEmail()).password(user.getPasswordHash())
                .disabled(!user.isAtivo() || !user.isPasswordDefinida())
                .roles(user.getPapeis().stream().map(Enum::name).toArray(String[]::new)).build();
    }
}
