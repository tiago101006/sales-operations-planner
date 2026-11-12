package pt.tiagodias.sop.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvitationService {
    private final IdentityService identities;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    public InvitationService(IdentityService identities, JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.identities = identities;this.jdbc = jdbc;this.encoder = encoder;
    }

    @Transactional
    public ConviteDto create(String nome, String email, Set<Papel> papeis, String actorEmail) {
        long actor = administrator(actorEmail);
        var user = identities.createPending(nome, email, papeis);
        return issue(Long.valueOf(user.id()), user.versao(), actor);
    }

    @Transactional
    public ConviteDto reissue(Long id, long version, String actorEmail) {
        return issue(id, version, administrator(actorEmail));
    }

    private long administrator(String email) {
        var ids = jdbc.queryForList("SELECT u.id FROM utilizador u JOIN utilizador_papel p ON p.utilizador_id=u.id "
                + "WHERE u.email=? AND u.ativo=TRUE AND u.password_definida=TRUE AND p.papel='ADMIN'", Long.class, email);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso recusado.");
        return ids.get(0);
    }

    private ConviteDto issue(Long id, long version, long actor) {
        var rows = jdbc.queryForList("SELECT ativo,password_definida,versao_acesso FROM utilizador WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilizador não encontrado.");
        var row = rows.get(0);
        if (!Boolean.TRUE.equals(row.get("ativo")) || Boolean.TRUE.equals(row.get("password_definida"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só podes gerar convites para contas ativas que ainda não escolheram a password.");
        }
        if (((Number) row.get("versao_acesso")).longValue() != version) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A conta foi alterada. Consulta novamente o utilizador.");
        }
        jdbc.update("UPDATE convite_utilizador SET revogado_em=UTC_TIMESTAMP(6) WHERE utilizador_id=? AND usado_em IS NULL AND revogado_em IS NULL", id);
        byte[] bytes = new byte[32];random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO convite_utilizador(utilizador_id,criado_por,token_hash,expira_em) VALUES (?,?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 24 HOUR))", id, actor, hash(token));
        jdbc.update("UPDATE utilizador SET versao_acesso=versao_acesso+1 WHERE id=?", id);
        var user = jdbc.queryForObject("SELECT id,nome,email,ativo,versao_acesso,password_definida FROM utilizador WHERE id=?",
                (rs,n) -> new IdentityService.UtilizadorDto(rs.getString("id"), rs.getString("nome"), rs.getString("email"),
                    Set.copyOf(jdbc.queryForList("SELECT papel FROM utilizador_papel WHERE utilizador_id=?", String.class, id).stream().map(Papel::valueOf).toList()),
                    rs.getBoolean("ativo"),rs.getLong("versao_acesso"),rs.getBoolean("password_definida")), id);
        Instant expires = jdbc.queryForObject("SELECT expira_em FROM convite_utilizador WHERE token_hash=?",
                (rs,n) -> rs.getTimestamp(1).toInstant(), hash(token));
        return new ConviteDto(user, token, expires);
    }

    @Transactional
    public void activate(String token, String password, String confirmation) {
        if (!password.equals(confirmation)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "As passwords não coincidem.");
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A password exige pelo menos 12 caracteres e no máximo 72 bytes UTF-8.");
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String digest = hash(token);
        var ids = jdbc.queryForList("SELECT utilizador_id FROM convite_utilizador WHERE token_hash=?", Long.class, digest);
        if (ids.isEmpty()) throw invalid();
        Long id = ids.get(0);
        // Lock the account before the invitation, as in reissue. Parallel redemptions cannot both succeed.
        var accounts = jdbc.queryForList("SELECT ativo,password_definida FROM utilizador WHERE id=? FOR UPDATE", id);
        var account = accounts.get(0);
        var invitations = jdbc.queryForList("SELECT id FROM convite_utilizador WHERE token_hash=? AND usado_em IS NULL AND revogado_em IS NULL AND expira_em>UTC_TIMESTAMP(6) FOR UPDATE", Long.class, digest);
        if (!Boolean.TRUE.equals(account.get("ativo")) || Boolean.TRUE.equals(account.get("password_definida")) || invitations.isEmpty()) throw invalid();
        jdbc.update("UPDATE utilizador SET password_hash=?,password_definida=TRUE,versao_acesso=versao_acesso+1 WHERE id=?", encoder.encode(password), id);
        jdbc.update("UPDATE convite_utilizador SET usado_em=UTC_TIMESTAMP(6) WHERE id=?", invitations.get(0));
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "O convite é inválido ou expirou. Pede um novo convite ao administrador.");
    }
    public record ConviteDto(IdentityService.UtilizadorDto utilizador, String token, Instant expiraEm) {}
}
