package pt.tiagodias.sop.identity;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountProfileService {
    private final UtilizadorRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public AccountProfileService(UtilizadorRepository users, JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.users = users;
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Transactional
    public void changePassword(String email, long version, String current, String password, String confirmation) {
        if (!password.equals(confirmation)) {
            throw invalid("A nova palavra-passe e a confirmação não coincidem.");
        }
        IdentityService.validatePassword(password);
        var user = users.findByEmail(email)
                .filter(u -> u.isAtivo() && u.isPasswordDefinida() && u.getVersao() == version)
                .orElseThrow(AccountProfileService::revoked);
        if (current.getBytes(StandardCharsets.UTF_8).length > 72 || !encoder.matches(current, user.getPasswordHash())) {
            throw invalid("A palavra-passe atual está incorreta.");
        }
        if (current.equals(password)) {
            throw invalid("Escolhe uma palavra-passe diferente da atual.");
        }
        // The entity version advances on update, invalidating all existing sessions.
        user.alterarPassword(encoder.encode(password));
        users.flush();
    }

    public boolean hasPhoto(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM foto_perfil WHERE utilizador_id=?)", Boolean.class, id));
    }

    public byte[] photo(String email, long version) {
        return jdbc.query("""
                SELECT f.imagem FROM foto_perfil f JOIN utilizador u ON u.id=f.utilizador_id
                WHERE u.email=? AND u.ativo=TRUE AND u.password_definida=TRUE AND u.versao_acesso=?
                """, (rs, row) -> rs.getBytes("imagem"), email, version)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "A conta ainda não tem foto."));
    }

    @Transactional
    public void savePhoto(String email, long version, byte[] image) {
        long id = lockAccount(email, version);
        jdbc.update("""
                INSERT INTO foto_perfil (utilizador_id, imagem) VALUES (?, ?) AS nova
                ON DUPLICATE KEY UPDATE imagem=nova.imagem
                """, id, image);
    }

    @Transactional
    public void removePhoto(String email, long version) {
        jdbc.update("DELETE FROM foto_perfil WHERE utilizador_id=?", lockAccount(email, version));
    }

    private long lockAccount(String email, long version) {
        // Recheck the session version after decoding the upload and serialize photo writes.
        return jdbc.queryForList("""
                SELECT id FROM utilizador
                WHERE email=? AND ativo=TRUE AND password_definida=TRUE AND versao_acesso=?
                FOR UPDATE
                """, Long.class, email, version).stream().findFirst().orElseThrow(AccountProfileService::revoked);
    }

    private static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    private static ResponseStatusException revoked() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A sessão terminou. Volta a entrar.");
    }
}
