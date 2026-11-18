package pt.tiagodias.sop.identity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.scenarios.ScenarioDtos.Pagina;
import pt.tiagodias.sop.shared.SearchPage;

@Service
public class IdentityService {
    private final UtilizadorRepository repository;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final AccountProfileService profile;

    public IdentityService(UtilizadorRepository repository, PasswordEncoder encoder,
                           JdbcTemplate jdbc, AccountProfileService profile) {
        this.repository = repository;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.profile = profile;
    }

    @Transactional(readOnly = true)
    public Pagina<UtilizadorDto> list(int page, int size) {
        var result = SearchPage.page(jdbc,
                """
                SELECT u.id,u.nome,u.email,u.ativo,u.versao_acesso,u.password_definida,
                       (SELECT GROUP_CONCAT(papel ORDER BY papel) FROM utilizador_papel WHERE utilizador_id=u.id) papeis,
                       EXISTS (SELECT 1 FROM foto_perfil WHERE utilizador_id=u.id) tem_foto
                FROM utilizador u
                """,
                "nome,id", (rs, row) -> new UtilizadorDto(
                        rs.getString("id"), rs.getString("nome"), rs.getString("email"),
                        Arrays.stream(rs.getString("papeis").split(",")).map(Papel::valueOf).collect(Collectors.toSet()),
                        rs.getBoolean("ativo"), rs.getLong("versao_acesso"), rs.getBoolean("password_definida"),
                        rs.getBoolean("tem_foto")),
                page, size, "id,nome,email,papeis,REPLACE(papeis,'ADMIN','Administrador'),"
                        + "IF(NOT ativo,'Inativo',IF(password_definida,'Ativo','Por ativar'))", null);
        return new Pagina<>(result.items(), page, size, result.total(), result.pages());
    }

    @Transactional(readOnly = true)
    public UtilizadorDto get(long id) {
        return repository.findById(id).map(user -> dto(user, profile.hasPhoto(id)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilizador não encontrado."));
    }

    public static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    static void validatePassword(String password) {
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A password exige pelo menos 12 caracteres e no máximo 72 bytes UTF-8.");
        }
    }

    @Transactional
    public UtilizadorDto create(String nome, String email, String password, Set<Papel> papeis) {
        String normalized = normalizeEmail(email);
        validatePassword(password);
        if (repository.existsByEmail(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O email já está registado.");
        }
        var user = repository.saveAndFlush(new Utilizador(nome.strip(), normalized, encoder.encode(password), papeis));
        return dto(user, false);
    }

    @Transactional
    public UtilizadorDto createPending(String nome, String email, Set<Papel> papeis) {
        String normalized = normalizeEmail(email);
        if (repository.existsByEmail(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O email já está registado.");
        }
        // An unknowable placeholder keeps the non-null BCrypt invariant until activation.
        byte[] random = new byte[32];new java.security.SecureRandom().nextBytes(random);
        var user = new Utilizador(nome.strip(), normalized,
                encoder.encode(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random)), papeis);
        user.pendenteAtivacao();
        return dto(repository.saveAndFlush(user), false);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public UtilizadorDto createFirstAdmin(String nome, String email, String password) {
        if (repository.count() != 0) {
            throw new IllegalStateException("Bootstrap recusado: já existem utilizadores. Usar uma conta ADMIN existente.");
        }
        return create(nome, email, password, Set.of(Papel.ADMIN));
    }

    @Transactional
    public UtilizadorDto changeStatus(Long id, boolean ativo, long versaoEsperada, String actorEmail) {
        // Serialize account lifecycle changes, including simultaneous administrator departures.
        jdbc.queryForList("SELECT u.id FROM utilizador u JOIN utilizador_papel p ON p.utilizador_id=u.id "
                + "WHERE p.papel='ADMIN' ORDER BY u.id FOR UPDATE", Long.class);
        var actor = repository.findByEmail(actorEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso recusado."));
        if (!actor.isAtivo() || !actor.getPapeis().contains(Papel.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso recusado.");
        }
        var user = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilizador não encontrado."));
        if (!ativo && actor.getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Não podes desativar a tua própria conta. Pede a outro administrador.");
        }
        if (user.getVersao() != versaoEsperada) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A conta foi alterada. Fecha esta janela e consulta novamente o utilizador.");
        }
        if (user.isAtivo() == ativo) return dto(user, profile.hasPhoto(id));
        boolean anterior = user.isAtivo();
        user.setAtivo(ativo);
        repository.flush();
        if (!ativo) jdbc.update("UPDATE convite_utilizador SET revogado_em=UTC_TIMESTAMP(6) WHERE utilizador_id=? AND usado_em IS NULL AND revogado_em IS NULL", id);
        jdbc.update("INSERT INTO evento_estado_utilizador(utilizador_id,alterado_por,ativo_anterior,ativo_novo) VALUES (?,?,?,?)",
                id, actor.getId(), anterior, ativo);
        return dto(user, profile.hasPhoto(id));
    }

    private static UtilizadorDto dto(Utilizador user, boolean temFoto) {
        return new UtilizadorDto(user.getId().toString(), user.getNome(), user.getEmail(), user.getPapeis(),
                user.isAtivo(), user.getVersao(), user.isPasswordDefinida(), temFoto);
    }

    public record UtilizadorDto(String id, String nome, String email, Set<Papel> papeis, boolean ativo,
                                long versao, boolean passwordDefinida, boolean temFoto) {}
}
