package pt.tiagodias.sop.identity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {
    private final UtilizadorRepository repository;
    private final PasswordEncoder encoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public IdentityService(UtilizadorRepository repository, PasswordEncoder encoder, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repository = repository;
        this.encoder = encoder;this.jdbc=jdbc;
    }

    @Transactional(readOnly=true)
    public pt.tiagodias.sop.scenarios.ScenarioDtos.Pagina<UtilizadorDto> list(int page,int size) {
        var result=pt.tiagodias.sop.shared.SearchPage.page(jdbc,
                "SELECT u.id,u.nome,u.email,(SELECT GROUP_CONCAT(papel ORDER BY papel) FROM utilizador_papel WHERE utilizador_id=u.id) papeis FROM utilizador u",
                "nome,id",(rs,n)->new UtilizadorDto(rs.getString("id"),rs.getString("nome"),rs.getString("email"),
                    java.util.Arrays.stream(rs.getString("papeis").split(",")).map(Papel::valueOf).collect(java.util.stream.Collectors.toSet())),
                page,size,"id,nome,email,papeis,REPLACE(papeis,'ADMIN','Administrador')",null);
        return new pt.tiagodias.sop.scenarios.ScenarioDtos.Pagina<>(result.items(),page,size,result.total(),result.pages());
    }

    public static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    @Transactional
    public UtilizadorDto create(String nome, String email, String password, Set<Papel> papeis) {
        String normalized = normalizeEmail(email);
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A password exige pelo menos 12 caracteres e no máximo 72 bytes UTF-8.");
        }
        if (repository.existsByEmail(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O email já está registado.");
        }
        var user = repository.saveAndFlush(new Utilizador(nome.strip(), normalized, encoder.encode(password), papeis));
        return dto(user);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public UtilizadorDto createFirstAdmin(String nome, String email, String password) {
        if (repository.count() != 0) {
            throw new IllegalStateException("Bootstrap recusado: já existem utilizadores. Usar uma conta ADMIN existente.");
        }
        return create(nome, email, password, Set.of(Papel.ADMIN));
    }

    public static UtilizadorDto dto(Utilizador user) {
        return new UtilizadorDto(user.getId().toString(), user.getNome(), user.getEmail(), user.getPapeis());
    }

    public record UtilizadorDto(String id, String nome, String email, Set<Papel> papeis) {}
}
